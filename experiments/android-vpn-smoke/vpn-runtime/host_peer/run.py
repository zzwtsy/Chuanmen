"""启动已授权开发主机的独立 EasyTier/回显对端；退出时只清理本脚本创建的资源。"""

import argparse
import hashlib
import ipaddress
import json
import os
import signal
import socket
import subprocess
import threading
import time
import uuid
from datetime import UTC, datetime
from pathlib import Path

from benchmark_endpoints import BenchmarkEndpoints

CORE = "7592951b808c4839cb9d77cc4bc642f124915951"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-report", type=Path, required=True)
    parser.add_argument(
        "--bind-ip", required=True, help="已核验通向受控手机的开发主机私有 IPv4"
    )
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--duration", type=int, default=180)
    parser.add_argument(
        "--proxy-diagnostics",
        action="store_true",
        help="只增加代理的 info 元数据，不启用包内容 trace",
    )
    parser.add_argument(
        "--advanced",
        action="store_true",
        help="本机回环转发到手机合成子网，并提供私有 LAN 出口回显",
    )
    parser.add_argument(
        "--benchmark", action="store_true", help="增加只在回环监听的合成持续传输端点"
    )
    args = parser.parse_args()
    ip = ipaddress.ip_address(args.bind_ip)
    if ip.version != 4 or not ip.is_private or ip.is_loopback or ip.is_unspecified:
        parser.error("只允许明确的局域网私有 IPv4")
    if not 10 <= args.duration <= 7200:
        parser.error("实验存活时间必须为 10–7200 秒")
    if args.out.exists():
        parser.error("禁止覆盖已有对端实验")
    build = json.loads(args.build_report.read_text())
    if (
        build.get("status") != "passed"
        or build.get("core_commit") != CORE
        or build.get("patches") != []
    ):
        parser.error("只接受固定核心无补丁的成功对端构建")
    features = set(build.get("features", "").split(","))
    if not {
        "management",
        "smoltcp",
        "tun",
        "aes-gcm",
    } <= features or not features.intersection({"kcp", "quic"}):
        parser.error(
            "对端必须同时包含用户态协议栈、加密及固定版本实际启用包代理的编译特性"
        )
    if args.advanced and "socks5" not in features:
        parser.error(
            "高级 host port_forward 的运行 gateway 必须包含真实 socks5 编译特性"
        )
    binary = Path(build["binary_path"])
    if hashlib.sha256(binary.read_bytes()).hexdigest() != build["binary_sha256"]:
        parser.error("对端二进制哈希不匹配")
    args.out.mkdir(parents=True)
    run_id = "p00-" + uuid.uuid4().hex[:12]
    report = {
        "schema_version": 1,
        "layer": "controlled_host_peer",
        "status": "failed",
        "run_id": run_id,
        "core_commit": CORE,
        "binary_sha256": build["binary_sha256"],
        "build_report_sha256": hashlib.sha256(
            args.build_report.read_bytes()
        ).hexdigest(),
        "build_features": build["features"],
        "recorded_at_utc": datetime.now(UTC).isoformat(),
        "host_bind": args.bind_ip + ":31270",
        "virtual_ip": "10.222.0.1/24",
        "tcp_echo": "127.0.0.1:31271",
        "udp_echo": "127.0.0.1:31272",
        "requires_host_tun": False,
        "public_discovery": False,
        "system_routes_modified": False,
        "stop_request_path": str(args.out.resolve() / "stop.request"),
        "advanced": args.advanced,
        "benchmark": args.benchmark,
        "benchmark_endpoint_sha256": hashlib.sha256(
            Path(__file__).with_name("benchmark_endpoints.py").read_bytes()
        ).hexdigest()
        if args.benchmark
        else None,
        "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "commands": [],
    }
    stopping = threading.Event()
    threads, sockets, process = [], [], None
    benchmark = None
    records = args.out / "echo.jsonl"
    lock = threading.Lock()

    def record(proto, data, addr):
        if not data.startswith((run_id + ":").encode()) or len(data) > 4096:
            return False
        item = {
            "protocol": proto,
            "utc": datetime.now(UTC).isoformat(),
            "bytes": len(data),
            "request_sha256": hashlib.sha256(data).hexdigest(),
            "source": list(addr),
        }
        with lock, records.open("a") as stream:
            stream.write(json.dumps(item) + "\n")
        return True

    def tcp_echo(server):
        while not stopping.is_set():
            try:
                client, addr = server.accept()
            except TimeoutError:
                continue
            with client:
                client.settimeout(5)
                data = b""
                try:
                    while not data.endswith(b"\n") and len(data) <= 4096:
                        chunk = client.recv(4096)
                        if not chunk:
                            break
                        data += chunk
                    if record(
                        "tcp_exit" if server.getsockname()[1] == 31291 else "tcp",
                        data,
                        addr,
                    ):
                        client.sendall(data)
                except (TimeoutError, OSError):
                    continue

    def udp_echo(server):
        while not stopping.is_set():
            try:
                data, addr = server.recvfrom(4097)
            except TimeoutError:
                continue
            if record("udp", data, addr):
                server.sendto(data, addr)

    env = {k: v for k, v in os.environ.items() if not k.startswith("ET_")}
    env["TMPDIR"] = str(args.out.resolve())
    # 覆盖宿主继承的过滤器，避免意外开启包内容日志；只记录本次受控转发的连接元数据。
    env["RUST_LOG"] = (
        "warn,easytier_core::gateway::proxy::tcp_proxy_engine=info,easytier_core::gateway::proxy::tcp_proxy_service=info,easytier_core::gateway::stack=info"
        if args.proxy_diagnostics
        else "warn"
    )
    report["log_filter"] = env["RUST_LOG"]
    try:
        if args.benchmark:
            benchmark = BenchmarkEndpoints(run_id, args.out / "benchmark.jsonl")
        # 仅绑定明确的回环测试端口；已有占用会使实验失败，不寻找或终止其他服务。
        endpoints = [
            (socket.SOCK_STREAM, 31271, tcp_echo),
            (socket.SOCK_DGRAM, 31272, udp_echo),
        ]
        if args.advanced:
            endpoints.append((socket.SOCK_STREAM, 31291, tcp_echo))
        for kind, port, worker in endpoints:
            server = socket.socket(socket.AF_INET, kind)
            if kind == socket.SOCK_STREAM:
                server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sockets.append(server)
            server.bind((args.bind_ip if port == 31291 else "127.0.0.1", port))
            server.settimeout(0.5)
            if kind == socket.SOCK_STREAM:
                server.listen(4)
            thread = threading.Thread(target=worker, args=(server,), daemon=True)
            thread.start()
            threads.append(thread)
        config = (
            f'instance_name = "p00-host-peer"\nhostname = "p00-host-peer"\nipv4 = "10.222.0.1/24"\n'
            f'listeners = ["tcp://{args.bind_ip}:31270"]\npeer = []\ndhcp = false\n'
            "stun_servers = []\ntcp_stun_servers = []\nstun_servers_v6 = []\n"
            '[network_identity]\nnetwork_name = "chuanmen-p00-isolated"\n'
            'network_secret = "p00-synthetic-public-test-only"\n'
            "[flags]\nno_tun = true\nuse_smoltcp = true\nenable_ipv6 = false\n"
            "disable_upnp = true\ndisable_udp_hole_punching = true\ndisable_tcp_hole_punching = true\n"
            "disable_p2p = true\nprivate_mode = true\n"
        )
        if args.advanced:
            config = config.replace(
                "dhcp = false\n", 'dhcp = false\nexit_nodes = ["10.222.0.2"]\n'
            )
            for proto, bind, destination in [
                ("tcp", 31381, "10.223.0.1:31281"),
                ("udp", 31382, "10.223.0.1:31282"),
                ("tcp", 31391, args.bind_ip + ":31291"),
            ]:
                config += f'\n[[port_forward]]\nproto = "{proto}"\nbind_addr = "127.0.0.1:{bind}"\ndst_addr = "{destination}"\n'
            report["advanced_forward_ports"] = {
                "subnet_tcp": 31381,
                "subnet_udp": 31382,
                "exit_tcp": 31391,
            }
        path = args.out.resolve() / "host.toml"
        path.write_text(config)
        report["config_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        command = [
            str(binary),
            "--config-file",
            str(path),
            "--disable-env-parsing",
            "--rpc-portal",
            "127.0.0.1:31273",
            "--machine-id",
            "p00-synthetic-host-peer",
            "--state-dir",
            str(args.out.resolve() / "state"),
            "--console-log-level",
            "warn",
            "--file-log-level",
            "off",
        ]
        checked = subprocess.run(
            [*command, "--check-config"],
            check=False,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=30,
        )
        report["commands"].append(
            {
                "argv": [*command, "--check-config"],
                "exit_code": checked.returncode,
                "output": checked.stdout,
            }
        )
        if checked.returncode:
            raise RuntimeError("对端配置检查失败")
        with (args.out / "peer.txt").open("x") as stream:
            process = subprocess.Popen(
                command,
                env=env,
                stdout=stream,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        report["commands"].append({"argv": command, "pid": process.pid})
        deadline = time.monotonic() + 15
        ready = False
        while time.monotonic() < deadline:
            if process.poll() is not None:
                raise RuntimeError("独立对端进程提前退出")
            with socket.socket() as probe:
                probe.settimeout(0.2)
                if probe.connect_ex((args.bind_ip, 31270)) == 0:
                    ready = True
                    break
            time.sleep(0.2)
        if not ready:
            raise RuntimeError("对端监听未就绪")
        report["status"] = "ready"
        ready_text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        (args.out / "peer-ready.json").write_text(ready_text)
        (args.out / "peer-report.json").write_text(ready_text)
        print(
            json.dumps(
                {
                    "status": "ready",
                    "run_id": run_id,
                    "report": str(args.out / "peer-report.json"),
                }
            ),
            flush=True,
        )
        deadline = time.monotonic() + args.duration
        while time.monotonic() < deadline and not stopping.wait(0.5):
            if (args.out / "stop.request").exists():
                report["stop_requested"] = True
                break
            if process.poll() is not None:
                raise RuntimeError("对端在实验中退出")
        report["status"] = "stopped"
    except (
        OSError,
        ValueError,
        RuntimeError,
        subprocess.TimeoutExpired,
        KeyboardInterrupt,
    ) as error:
        report["status"] = "failed"
        report["error"] = str(error)
    finally:
        if benchmark is not None:
            try:
                benchmark.close()
                report["benchmark_threads_stopped"] = True
            except (OSError, RuntimeError) as error:
                report["benchmark_threads_stopped"] = False
                report["benchmark_cleanup_error"] = str(error)
        stopping.set()
        if process and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
        for thread in threads:
            thread.join(timeout=6)
        for server in sockets:
            server.close()
        report["peer_exit_code"] = process.returncode if process else None
        report["echo_threads_stopped"] = all(not t.is_alive() for t in threads)
        report["echo_records"] = (
            len(records.read_text().splitlines()) if records.exists() else 0
        )
        for name in ("host.toml", "peer.txt", "echo.jsonl", "benchmark.jsonl"):
            path = args.out / name
            if path.exists():
                report[name + "_sha256"] = hashlib.sha256(path.read_bytes()).hexdigest()
        (args.out / "peer-report.json").write_text(
            json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        )
        print(
            json.dumps(
                {
                    "status": report["status"],
                    "echo_records": report["echo_records"],
                    "report": str(args.out / "peer-report.json"),
                }
            ),
            flush=True,
        )
    return (
        0
        if report["status"] == "stopped"
        and report["echo_threads_stopped"]
        and report.get("benchmark_threads_stopped", True)
        else 2
    )


if __name__ == "__main__":
    raise SystemExit(main())
