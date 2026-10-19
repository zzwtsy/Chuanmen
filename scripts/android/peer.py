"""受控用户态对端的构建与运行；不创建宿主 TUN 或修改系统路由。"""

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

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "native"))
import build_native

ROOT = build_native.ROOT
PEER = ROOT / "scripts/android/native-peer"
OUTPUT = ROOT / "app/build/device-tests/peer"
BINARY = ROOT / "app/build/device-tests/peer-target/debug/chuanmen-controlled-peer"


def source_files() -> dict[str, str]:
    names = [
        *PEER.glob("src/*.rs"),
        PEER / "Cargo.toml",
        PEER / "Cargo.lock",
        Path(__file__).resolve(),
    ]
    return {
        str(path.relative_to(ROOT)): build_native.digest(path) for path in sorted(names)
    }


def validate_report(
    report: dict, sources: dict, inputs: dict, native_hash: str, binary: Path
) -> None:
    """固定来源、资源范围和实际字节均需对应本次输入；不能用任意 passed 字段放行。"""
    expected = {
        "schema_version": 1,
        "status": "passed",
        "role": "controlled_dns_peer",
        "core_commit": inputs["core_commit"],
        "patch_id": inputs["patch_id"],
        "rustc_commit": inputs["rustc_commit"],
        "source_files": sources,
        "native_inputs_sha256": native_hash,
        "virtual_dns_port": 53,
        "binary_sha256": build_native.digest(binary),
    }
    if (
        any(report.get(key) != value for key, value in expected.items())
        or report.get("host_tun") is not False
        or report.get("system_routes_modified") is not False
    ):
        raise ValueError("受控对端产物与本次输入或资源范围不匹配")


def build_peer(action: str, allow_download: bool) -> int:
    output = OUTPUT
    output.mkdir(parents=True, exist_ok=True)
    try:
        inputs = build_native.inputs()
        build_native.prepare(inputs, allow_download)
        compiler = build_native.capture(["rustc", f"+{inputs['rust_version']}", "-Vv"])
        if f"commit-hash: {inputs['rustc_commit']}" not in compiler:
            raise ValueError("受控对端编译器不匹配")
        sources = source_files()
        native_hash = build_native.input_fingerprint()
        report_path = output / "build-report.json"
        binary = BINARY
        if action == "check":
            report = json.loads(report_path.read_text())
            validate_report(report, sources, inputs, native_hash, binary)
        else:
            env = os.environ.copy()
            if any(
                env.get(key)
                for key in ("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "CARGO_TARGET_DIR")
            ):
                raise ValueError("受控对端存在未登记的编译参数")
            env.update(
                CARGO_BUILD_JOBS="2",
                CARGO_INCREMENTAL="0",
                CARGO_TARGET_DIR=str(BINARY.parents[1]),
            )
            command = [
                "cargo",
                f"+{inputs['rust_version']}",
                "build",
                "--locked",
                "--manifest-path",
                str(PEER / "Cargo.toml"),
            ]
            if not allow_download:
                command.append("--offline")
            with (output / "build.log").open("w") as stream:
                subprocess.run(
                    command,
                    env=env,
                    cwd=ROOT,
                    stdout=stream,
                    stderr=subprocess.STDOUT,
                    check=True,
                )
            if (
                source_files() != sources
                or build_native.input_fingerprint() != native_hash
            ):
                raise ValueError("受控对端构建期间输入变化")
            build_native.prepare(inputs, allow_download)
            report = {
                "schema_version": 1,
                "status": "passed",
                "role": "controlled_dns_peer",
                "core_commit": inputs["core_commit"],
                "patch_id": inputs["patch_id"],
                "native_inputs_sha256": native_hash,
                "source_files": sources,
                "rustc_commit": inputs["rustc_commit"],
                "host_tun": False,
                "system_routes_modified": False,
                "virtual_dns_port": 53,
                "binary_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
                "binary_path": str(binary.relative_to(ROOT)),
            }
            report_path.write_text(json.dumps(report, indent=2) + "\n")
        print("CONTROLLED_PEER_BUILD_OK: 本次固定核心与用户态 DNS；未执行设备验收")
        return 0
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f"CONTROLLED_PEER_BUILD_FAILED: {error}")
        return 1


def run_peer(args: argparse.Namespace) -> int:
    address = ipaddress.IPv4Address(args.bind_ip)
    if not address.is_private or address.is_loopback or address.is_unspecified:
        raise ValueError("仅允许已核验的私有 LAN IPv4")
    if not 10 <= args.duration <= 1800 or not args.run_id.startswith("p02-"):
        raise ValueError("预算须为 10–1800 秒，并提供独立 p02-* 请求身份")
    ipv6 = ipaddress.IPv6Address(args.ipv6_address) if args.ipv6_address else None
    if ipv6 is not None and not ipv6.is_global:
        raise ValueError("IPv6 基线须使用已核验的全局单播地址")
    args.binary = BINARY
    args.build_report = OUTPUT / "build-report.json"
    build = json.loads(args.build_report.read_text())
    native = build_native.inputs()
    build_native.verify_checkout(ROOT / "app/build/native/upstream", native)
    validate_report(
        build, source_files(), native, build_native.input_fingerprint(), args.binary
    )
    binary_hash = build_native.digest(args.binary)
    args.out.mkdir(parents=True, exist_ok=False)
    report = {
        "run_id": args.run_id,
        "status": "failed",
        "core_commit": native["core_commit"],
        "binary_sha256": binary_hash,
        "build_report_sha256": hashlib.sha256(
            args.build_report.read_bytes()
        ).hexdigest(),
        "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "host_tun": False,
        "system_routes_modified": False,
        "echo_records": 0,
        "virtual_dns_port": 53,
        "peer_status_port": 31274,
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
            with (args.out / "physical-echo.jsonl").open("a") as stream:
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
        listeners = []
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
                    (args.out / "dns-ready.json").exists()
                ):
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
        stopped = args.out / "dns-stopped.json"
        state = json.loads(stopped.read_text()) if stopped.exists() else {}
        report["dns_socket_stopped"] = (
            state.get("run_id") == args.run_id
            and state.get("state") == "Stopped"
            and state.get("dns_socket_dropped") is True
        )
        if not report["dns_socket_stopped"]:
            report["status"] = "failed"
        virtual_echo = args.out / "echo.jsonl"
        if virtual_echo.exists():
            report["echo_records"] += len(virtual_echo.read_text().splitlines())
        (args.out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    return (
        0
        if report["status"] == "stopped"
        and report["echo_threads_stopped"]
        and report["peer_stopped"]
        else 1
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("build", "check", "run"))
    parser.add_argument(
        "--allow-download", action="store_true", help="首次构建显式允许获取固定依赖"
    )
    parser.add_argument("--bind-ip", help="受控主机私有 LAN IPv4")
    parser.add_argument("--run-id", help="本次独立 p02-* 请求身份")
    parser.add_argument(
        "--out", type=Path, help="新的运行目录，默认 app/build/device-tests/peer/run-*"
    )
    parser.add_argument("--duration", type=int, default=600, help="运行预算 10–1800 秒")
    parser.add_argument(
        "--ipv6-address", help="实际全局 IPv6，仅用于物理网络正向基线与阻断检查"
    )
    args = parser.parse_args()
    if args.action != "run":
        return build_peer(args.action, args.allow_download)
    if not args.bind_ip or not args.run_id:
        parser.error("run 需要 --bind-ip 和 --run-id")
    if args.out is None:
        args.out = OUTPUT / f"run-{time.time_ns()}"
    try:
        return run_peer(args)
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f"CONTROLLED_PEER_FAILED: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
