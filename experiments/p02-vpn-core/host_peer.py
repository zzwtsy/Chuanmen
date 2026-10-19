"""P02 受控对端：固定核心、私有 LAN 外层与回环回显，不创建宿主 TUN 或系统路由。"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
import signal
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

CORE = "7592951b808c4839cb9d77cc4bc642f124915951"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--build-report", type=Path, required=True)
    parser.add_argument("--bind-ip", required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--duration", type=int, default=600)
    parser.add_argument(
        "--ipv6-address", help="已核验的宿主全局 IPv6，仅用于物理网络正向基线和阻断验证"
    )
    parser.add_argument(
        "--dns-peer", action="store_true", help="使用固定正式来源的用户态 53 端口对端"
    )
    args = parser.parse_args()
    address = ipaddress.IPv4Address(args.bind_ip)
    if not address.is_private or address.is_loopback or address.is_unspecified:
        parser.error("仅允许已核验的私有 LAN IPv4")
    if not 10 <= args.duration <= 1800 or not args.run_id.startswith("p02-"):
        parser.error("预算须为 10–1800 秒，并提供独立 P02 请求身份")
    ipv6 = ipaddress.IPv6Address(args.ipv6_address) if args.ipv6_address else None
    if ipv6 is not None and not ipv6.is_global:
        parser.error("IPv6 基线须使用已核验的全局单播地址")
    build = json.loads(args.build_report.read_text())
    binary_hash = hashlib.sha256(args.binary.read_bytes()).hexdigest()
    if args.dns_peer:
        root = Path(__file__).resolve().parents[2]
        sys.path.insert(0, str(root / "scripts/native"))
        import build_native
        import build_peer

        try:
            native = build_native.inputs()
            build_native.verify_checkout(root / "build/native/upstream", native)
            build_peer.validate_report(
                build,
                build_peer.source_files(),
                native,
                build_native.digest(root / "native/inputs.json"),
                args.binary,
            )
        except (OSError, ValueError, KeyError, subprocess.SubprocessError):
            parser.error("用户态 DNS 对端须匹配本次固定正式来源与实际构建输入")
    elif (
        build.get("status") != "passed"
        or build.get("core_commit") != CORE
        or build.get("patches") != []
        or build.get("binary_sha256") != binary_hash
    ):
        parser.error("对端必须匹配固定、无补丁的成功构建；旧设备结论不参与本次判据")
    if not args.dns_peer and not {
        "management",
        "smoltcp",
        "tun",
        "aes-gcm",
        "quic",
    } <= set(build.get("features", "").split(",")):
        parser.error("缺少受控用户态包代理的编译特性")
    args.out.mkdir(parents=True, exist_ok=False)
    report = {
        "run_id": args.run_id,
        "status": "failed",
        "core_commit": CORE,
        "binary_sha256": binary_hash,
        "build_report_sha256": hashlib.sha256(
            args.build_report.read_bytes()
        ).hexdigest(),
        "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "host_tun": False,
        "system_routes_modified": False,
        "echo_records": 0,
        "virtual_dns_port": 53 if args.dns_peer else None,
        "peer_status_port": 31274 if args.dns_peer else None,
        "native_inputs_sha256": build.get("native_inputs_sha256"),
        "patch_id": build.get("patch_id"),
        "physical_ipv6_address": str(ipv6) if ipv6 else None,
    }
    stopping = threading.Event()
    lock = threading.Lock()
    sockets: list[socket.socket] = []
    threads: list[threading.Thread] = []
    process = None

    def record(protocol: str, payload: bytes) -> bool:
        if not payload.startswith((args.run_id + ":").encode()) or len(payload) > 4096:
            return False
        with lock:
            with (args.out / "echo.jsonl").open("a") as stream:
                stream.write(
                    json.dumps(
                        {
                            "protocol": protocol,
                            "request_sha256": hashlib.sha256(payload).hexdigest(),
                            "bytes": len(payload),
                        }
                    )
                    + "\n"
                )
            report["echo_records"] += 1
        return True

    def echo(server: socket.socket, protocol: str) -> None:
        while not stopping.is_set():
            try:
                if protocol.endswith("udp"):
                    data, sender = server.recvfrom(4097)
                    if record(protocol, data):
                        server.sendto(data, sender)
                else:
                    client, _ = server.accept()
                    with client:
                        client.settimeout(3)
                        data = b""
                        while not data.endswith(b"\n") and len(data) <= 4096:
                            chunk = client.recv(4096)
                            if not chunk:
                                break
                            data += chunk
                        if record(protocol, data):
                            client.sendall(data)
            except (TimeoutError, OSError):
                continue

    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda _sig, _frame: stopping.set())
    try:
        listeners = [
            ("tcp", socket.SOCK_STREAM, 31271, socket.AF_INET, "127.0.0.1"),
            ("udp", socket.SOCK_DGRAM, 31272, socket.AF_INET, "127.0.0.1"),
        ]
        if ipv6 is not None:
            listeners.extend(
                [
                    ("ipv6-tcp", socket.SOCK_STREAM, 31275, socket.AF_INET6, str(ipv6)),
                    ("ipv6-udp", socket.SOCK_DGRAM, 31276, socket.AF_INET6, str(ipv6)),
                ]
            )
        for protocol, kind, port, family, bind_address in listeners:
            server = socket.socket(family, kind)
            sockets.append(server)
            server.settimeout(0.5)
            if kind == socket.SOCK_STREAM:
                server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server.bind((bind_address, port))
            if kind == socket.SOCK_STREAM:
                server.listen(4)
            thread = threading.Thread(target=echo, args=(server, protocol), daemon=True)
            threads.append(thread)
            thread.start()
        configuration = args.out.resolve() / "host.toml"
        configuration.write_text(
            'instance_name = "p02-controlled-peer"\nhostname = "p02-controlled-peer"\nipv4 = "10.222.0.1/24"\n'
            f'listeners = ["tcp://{address}:31270", "udp://{address}:31270"]\ndhcp = false\n'
            "stun_servers = []\ntcp_stun_servers = []\nstun_servers_v6 = []\n"
            '[network_identity]\nnetwork_name = "chuanmen-p02-controlled"\nnetwork_secret = "p02-synthetic-public-test-only"\n'
            "[flags]\nno_tun = true\nuse_smoltcp = true\nenable_ipv6 = false\ndisable_upnp = true\n"
            "disable_udp_hole_punching = true\ndisable_tcp_hole_punching = true\ndisable_p2p = true\nprivate_mode = true\n"
        )
        env = {k: v for k, v in os.environ.items() if not k.startswith("ET_")}
        env.update(RUST_LOG="warn", TMPDIR=str(args.out.resolve()))
        command = [
            str(args.binary.resolve()),
            "--config-file",
            str(configuration),
            "--disable-env-parsing",
            "--rpc-portal",
            "127.0.0.1:31273",
            "--state-dir",
            str(args.out.resolve() / "state"),
            "--machine-id",
            "p02-controlled-peer",
            "--console-log-level",
            "warn",
            "--file-log-level",
            "off",
        ]
        if args.dns_peer:
            command = [
                str(args.binary.resolve()),
                "--config-file",
                str(configuration),
                "--out",
                str(args.out.resolve()),
                "--run-id",
                args.run_id,
                "--health-bind",
                f"{address}:31274",
            ]
        with (args.out / "peer.log").open("x") as stream:
            checked = subprocess.run(
                [*command, "--check-config"],
                env=env,
                stdout=stream,
                stderr=subprocess.STDOUT,
                timeout=30,
                check=False,
            )
            if checked.returncode:
                raise RuntimeError("受控配置拒绝")
            process = subprocess.Popen(
                command,
                env=env,
                stdout=stream,
                stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        deadline = time.monotonic() + 15
        while True:
            if process.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError("对端未在预算内就绪")
            with socket.socket() as probe:
                probe.settimeout(0.2)
                if probe.connect_ex((str(address), 31270)) == 0 and (
                    not args.dns_peer or (args.out / "dns-ready.json").exists()
                ):
                    if args.dns_peer:
                        dns = json.loads((args.out / "dns-ready.json").read_text())
                        if (
                            dns.get("run_id") != args.run_id
                            or dns.get("virtual_port") != 53
                        ):
                            raise RuntimeError("DNS 对端归属未确认")
                    break
            stopping.wait(0.2)
        report["status"] = "ready"
        (args.out / "ready.json").write_text(json.dumps(report, indent=2) + "\n")
        print("CONTROLLED_PEER_READY", flush=True)
        deadline = time.monotonic() + args.duration
        while not stopping.wait(0.5) and time.monotonic() < deadline:
            if (args.out / "stop.request").exists():
                break
            if process.poll() is not None:
                raise RuntimeError("对端提前退出")
        report["status"] = "stopped"
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        report["error"] = str(error)
    finally:
        stopping.set()
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=5)
        for thread in threads:
            thread.join(timeout=4)
        for server in sockets:
            server.close()
        report["echo_threads_stopped"] = all(
            not thread.is_alive() for thread in threads
        )
        report["peer_stopped"] = process is None or process.poll() is not None
        if args.dns_peer:
            stopped = args.out / "dns-stopped.json"
            state = json.loads(stopped.read_text()) if stopped.exists() else {}
            report["dns_socket_stopped"] = (
                state.get("run_id") == args.run_id
                and state.get("state") == "Stopped"
                and state.get("dns_socket_dropped") is True
            )
            if not report["dns_socket_stopped"]:
                report["status"] = "failed"
        (args.out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    return (
        0
        if report["status"] == "stopped"
        and report["echo_threads_stopped"]
        and report["peer_stopped"]
        else 1
    )


if __name__ == "__main__":
    raise SystemExit(main())
