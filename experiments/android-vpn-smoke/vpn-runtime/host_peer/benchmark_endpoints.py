"""受控性能端点：回环 TCP/UDP、合成数据和聚合哈希；不保存逐包内容。"""

import hashlib
import json
import socket
import struct
import threading
import time


class BenchmarkEndpoints:
    def __init__(self, run_id, output):
        self.run_id, self.output = run_id, output
        self.stopping = threading.Event()
        self.lock = threading.Lock()
        self.sockets, self.threads = [], []
        self.block = bytes(i % 251 for i in range(65536))
        try:
            tcp = socket.socket()
            tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.sockets.append(tcp)
            tcp.bind(("127.0.0.1", 31274))
            tcp.listen(2)
            tcp.settimeout(0.5)
            udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            self.sockets.append(udp)
            udp.bind(("127.0.0.1", 31275))
            udp.settimeout(0.5)
            self.threads = [
                threading.Thread(target=self.tcp, args=(tcp,)),
                threading.Thread(target=self.udp, args=(udp,)),
            ]
            for thread in self.threads:
                thread.start()
        except OSError:
            for endpoint in self.sockets:
                endpoint.close()
            raise

    def record(self, row):
        row["run_id"] = self.run_id
        with self.lock, self.output.open("a") as stream:
            stream.write(json.dumps(row) + "\n")

    def recv(self, endpoint, size):
        parts = bytearray()
        while len(parts) < size:
            part = endpoint.recv(size - len(parts))
            if not part:
                raise EOFError("性能 TCP 提前结束")
            parts.extend(part)
        return bytes(parts)

    def tcp(self, server):
        hello = (self.run_id + ":perf:tcp\n").encode()
        while not self.stopping.is_set():
            try:
                client, addr = server.accept()
            except TimeoutError:
                continue
            except OSError:
                return
            request, response = hashlib.sha256(), hashlib.sha256()
            frames, total = 0, 0
            started = time.monotonic_ns()
            completed = False
            error = None
            valid = False
            with client:
                client.settimeout(10)
                try:
                    if self.recv(client, len(hello)) != hello:
                        continue
                    valid = True
                    client.sendall(hello)
                    while not self.stopping.is_set():
                        length = struct.unpack("!I", self.recv(client, 4))[0]
                        if length == 0:
                            completed = True
                            break
                        if length != len(self.block):
                            raise ValueError("受控帧长度不符")
                        data = self.recv(client, length)
                        if data != self.block:
                            raise ValueError("合成块内容不符")
                        request.update(data)
                        client.sendall(data)
                        response.update(data)
                        frames += 1
                        total += length
                except (OSError, EOFError, ValueError) as failure:
                    error = str(failure)
            if valid:
                self.record(
                    {
                        "protocol": "tcp",
                        "frames": frames,
                        "bytes": total,
                        "request_sha256": request.hexdigest(),
                        "response_sha256": response.hexdigest(),
                        "completed": completed,
                        "error": error,
                        "elapsed_ns": time.monotonic_ns() - started,
                        "source": list(addr),
                    }
                )

    def udp(self, server):
        prefix = (self.run_id + ":perf:udp:").encode()
        end = (self.run_id + ":perf:udp:end\n").encode()
        digest = hashlib.sha256()
        frames, total = 0, 0
        started = None
        while not self.stopping.is_set():
            try:
                data, addr = server.recvfrom(2048)
            except TimeoutError:
                continue
            except OSError:
                return
            if data == end:
                server.sendto(data, addr)
                self.record(
                    {
                        "protocol": "udp",
                        "frames": frames,
                        "bytes": total,
                        "request_sha256": digest.hexdigest(),
                        "response_sha256": digest.hexdigest(),
                        "completed": True,
                        "elapsed_ns": None
                        if started is None
                        else time.monotonic_ns() - started,
                        "source": list(addr),
                    }
                )
                return
            if len(data) != 1024 or not data.startswith(prefix):
                continue
            if started is None:
                started = time.monotonic_ns()
            digest.update(data)
            frames += 1
            total += len(data)
            server.sendto(data, addr)

    def close(self):
        self.stopping.set()
        for endpoint in self.sockets:
            endpoint.close()
        for thread in self.threads:
            thread.join(12)
        if any(thread.is_alive() for thread in self.threads):
            raise RuntimeError("性能端点线程未释放")
