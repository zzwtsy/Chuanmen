"""对明确指定的已授权真机运行原生 instrumentation；依赖已有 VPN 授权。"""

import argparse
import hashlib
import json
import queue
import re
import socket
import subprocess
import sys
import threading
import time
import zipfile
from datetime import UTC, datetime
from pathlib import Path


def main():
    environment_kind = "physical_device"
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--build-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--native-boundary",
        action="store_true",
        help="仅验收自有补丁的 protect 和 TUN 借用，不计入双向流量门槛",
    )
    parser.add_argument(
        "--native-datapath",
        action="store_true",
        help="验证真实 TCP/UDP 往返并与受控主机回显记录对应",
    )
    parser.add_argument(
        "--native-revoke", action="store_true", help="等用户在系统设置实际撤销实验 VPN"
    )
    parser.add_argument(
        "--native-denied",
        action="store_true",
        help="点击真实系统授权对话框取消，核对未启动核心和服务",
    )
    parser.add_argument(
        "--native-address",
        action="store_true",
        help="实际地址、DHCP、TUN 参数替换与旧 JNI 快照过滤",
    )
    parser.add_argument(
        "--native-advanced",
        action="store_true",
        help="合成回显的高级能力风险探针，Portal 仅绑定",
    )
    parser.add_argument(
        "--connected-cycles", action="store_true", help="运行 20 轮连接中启动/回显/停止"
    )
    parser.add_argument(
        "--peer-report", type=Path, help="正在运行的独立开发主机对端报告"
    )
    args = parser.parse_args()
    if args.output.exists():
        parser.error("输出已存在，禁止覆盖实验记录")
    if (
        sum(
            (
                args.native_boundary,
                args.native_datapath,
                args.native_revoke,
                args.native_denied,
                args.native_address,
                args.native_advanced,
            )
        )
        != 1
    ):
        parser.error("必须明确选择且只选择一个原生实验模式")
    build = json.loads(args.build_report.read_text())
    if args.connected_cycles and not args.native_datapath:
        parser.error("连接循环仅用于原生数据通路实验")
    native = (
        args.native_boundary
        or args.native_datapath
        or args.native_revoke
        or args.native_denied
        or args.native_address
        or args.native_advanced
    )
    layer = (
        "android_native_advanced"
        if args.native_advanced
        else "android_native_address"
        if args.native_address
        else "android_native_permission_denial"
        if args.native_denied
        else "android_native_revoke"
        if args.native_revoke
        else "android_native_datapath"
        if args.native_datapath
        else "android_native_boundary"
    )
    build_layer = "android_native_boundary"
    if build.get("status") != "passed" or build.get("layer") != build_layer:
        parser.error("只接受本探针的成功构建报告")
    apk = Path(build["apk_path"])
    if hashlib.sha256(apk.read_bytes()).hexdigest() != build["apk_sha256"]:
        parser.error("APK 哈希与构建报告不一致")
    peer = None
    if (
        args.native_datapath
        or args.native_revoke
        or args.native_address
        or args.native_advanced
    ):
        if not args.peer_report:
            parser.error("数据通路实验必须提供受控主机对端报告")
        peer = json.loads(args.peer_report.read_text())
        if (
            peer.get("status") != "ready"
            or peer.get("core_commit") != build["core_commit"]
        ):
            parser.error("对端未就绪或核心 SHA 不一致")
        if args.native_advanced and peer.get("advanced") is not True:
            parser.error("高级探针需要 --advanced 的独立对端")
    if native:
        with zipfile.ZipFile(apk) as archive:
            for lib in build["libraries"]:
                data = archive.read("lib/" + lib["abi"] + "/libeasytier_android_jni.so")
                if hashlib.sha256(data).hexdigest() != lib["sha256"]:
                    parser.error("APK 内原生库哈希不匹配")
    report = {
        "schema_version": 1,
        "layer": layer,
        "status": "blocked",
        "environment_kind": environment_kind,
        "native_status": "unverified",
        "recorded_at_utc": datetime.now(UTC).isoformat(),
        "device_id": "device-01",
        "apk_sha256": build["apk_sha256"],
        "build_report_sha256": hashlib.sha256(
            args.build_report.read_bytes()
        ).hexdigest(),
        "commands": [],
    }
    report["runner_sha256"] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    instrumentation = (
        "/.AdvancedInstrumentation"
        if args.native_advanced
        else "/.AddressInstrumentation"
        if args.native_address
        else "/.PermissionDeniedInstrumentation"
        if args.native_denied
        else "/.RevokeInstrumentation"
        if args.native_revoke
        else "/.DatapathInstrumentation"
        if args.native_datapath
        else "/.BoundaryInstrumentation"
    )
    activity = "/.BoundaryActivity"
    if native:
        report.update(
            core_commit=build["core_commit"],
            patches=build["patches"],
            acceptance_scope="protect_callback_and_tun_borrow_only",
            vpn_acceptance="unverified",
        )
    if args.native_denied:
        report["acceptance_scope"] = "system_vpn_permission_denial"
    if peer:
        report["run_id"] = peer["run_id"]
        report["acceptance_scope"] = (
            "bounded_gateway_probes_portal_binding_only"
            if args.native_advanced
            else "address_and_platform_tun_replacement_with_scoped_generation_filter"
            if args.native_address
            else "system_vpn_revoke"
            if args.native_revoke
            else "tcp_udp_roundtrip_and_scoped_loop_prevention"
        )
        report["controlled_topology"] = {
            k: peer[k]
            for k in (
                "host_bind",
                "virtual_ip",
                "tcp_echo",
                "udp_echo",
                "requires_host_tun",
                "public_discovery",
                "system_routes_modified",
            )
        }
        report["peer_binary_sha256"] = peer["binary_sha256"]
        report["peer_ready_report_sha256"] = hashlib.sha256(
            args.peer_report.read_bytes()
        ).hexdigest()

    def adb(*command):
        result = subprocess.run(
            ["adb", "-s", args.serial, *command],
            check=False,
            timeout=90,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        report["commands"].append(
            {
                "argv": ["adb", "-s", "<controlled-serial>", *command],
                "exit_code": result.returncode,
                "output": result.stdout.replace(args.serial, "<controlled-serial>"),
            }
        )
        if result.returncode:
            raise RuntimeError(f"ADB 命令失败：{command[0]}")
        return result.stdout

    def instrument(package):
        # 条件：已授权且 APK 匹配；操作：等待设备阶段后启动页面；结果：保留完整输出或有界超时。
        command = ["shell", "am", "instrument", "-w", "-r", "-e", "host_launch", "true"]
        if peer:
            command += [
                "-e",
                "peer_host",
                peer["host_bind"].split(":")[0],
                "-e",
                "run_id",
                peer["run_id"],
            ]
            if args.connected_cycles:
                command += ["-e", "connected_cycles", "true"]
        command.append(package + instrumentation)
        process = subprocess.Popen(
            ["adb", "-s", args.serial, *command],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        lines = queue.Queue()

        def read_lines():
            for line in process.stdout:
                lines.put(line)
            lines.put(None)

        reader = threading.Thread(target=read_lines, daemon=True)
        reader.start()
        raw = []
        limit = 240 if args.native_revoke else 90
        deadline = time.monotonic() + limit
        launched = False
        try:
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise subprocess.TimeoutExpired(command, limit, output="".join(raw))
                try:
                    line = lines.get(timeout=min(remaining, 1))
                except queue.Empty:
                    continue
                if line is None:
                    break
                raw.append(line)
                if (
                    line.strip()
                    == "INSTRUMENTATION_STATUS: p00_phase=awaiting_system_revoke"
                ):
                    print(
                        json.dumps(
                            {
                                "phase": "awaiting_system_revoke",
                                "report": str(args.output),
                            },
                            ensure_ascii=False,
                        ),
                        flush=True,
                    )
                if (
                    line.strip()
                    == "INSTRUMENTATION_STATUS: p00_phase=awaiting_host_advanced_requests"
                ):
                    rows = []
                    for name, proto, port in (
                        ("subnet_tcp", "tcp", 31381),
                        ("subnet_udp", "udp", 31382),
                        ("exit_tcp", "tcp", 31391),
                    ):
                        prefix = (
                            "subnet:" + proto
                            if name.startswith("subnet_")
                            else "exit:tcp"
                        )
                        request = (peer["run_id"] + ":" + prefix + "\n").encode()
                        row = {
                            "name": name,
                            "protocol": proto,
                            "status": "failed",
                            "request_sha256": hashlib.sha256(request).hexdigest(),
                        }
                        try:
                            with socket.socket(
                                socket.AF_INET,
                                socket.SOCK_STREAM
                                if proto == "tcp"
                                else socket.SOCK_DGRAM,
                            ) as client:
                                client.settimeout(10)
                                client.connect(("127.0.0.1", port))
                                client.sendall(request)
                                response = b""
                                while len(response) < len(request):
                                    received = client.recv(4096)
                                    if not received:
                                        break
                                    response += received
                                row["response_sha256"] = hashlib.sha256(
                                    response
                                ).hexdigest()
                                if response != request:
                                    raise RuntimeError("宿主请求真实回包不匹配")
                                row["status"] = "passed"
                        except (OSError, RuntimeError) as error:
                            row["error"] = str(error)
                        rows.append(row)
                    report["host_advanced_requests"] = rows
                    adb(
                        "shell",
                        "run-as",
                        package,
                        "touch",
                        "files/p00-advanced-host-" + peer["run_id"] + ".done",
                    )
                if (
                    line.strip()
                    == "INSTRUMENTATION_STATUS: p00_phase=awaiting_activity"
                ):
                    if launched:
                        raise RuntimeError("设备重复请求 Activity，拒绝隐式重试")
                    adb("shell", "am", "start", "-n", package + activity)
                    launched = True
            process.wait(timeout=max(1, deadline - time.monotonic()))
            if process.returncode:
                raise RuntimeError("instrumentation 进程失败")
            if not launched:
                raise RuntimeError("设备未进入宿主启动阶段")
            return "".join(raw)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=5)
            reader.join(timeout=5)
            process.stdout.close()
            report["commands"].append(
                {
                    "argv": ["adb", "-s", "<controlled-serial>", *command],
                    "exit_code": process.returncode,
                    "output": "".join(raw).replace(args.serial, "<controlled-serial>"),
                }
            )

    try:
        if adb("get-state").strip() != "device":
            raise RuntimeError("指定设备未处于可调试状态")
        device = {}
        for key in (
            "ro.build.fingerprint",
            "ro.build.version.sdk",
            "ro.product.cpu.abi",
            "ro.product.manufacturer",
            "ro.product.model",
            "ro.kernel.qemu",
        ):
            device[key] = adb("shell", "getprop", key).strip()
        report["device"] = device
        if native:
            matching = [
                x
                for x in build["libraries"]
                if x["abi"] == device["ro.product.cpu.abi"]
            ]
            if len(matching) != 1:
                raise RuntimeError("未打包设备 ABI")
            report["native_library_sha256"] = matching[0]["sha256"]
        if environment_kind == "physical_device" and device["ro.kernel.qemu"] == "1":
            raise RuntimeError("本验收入口要求真机；模拟器只能单独记录辅助结果")
        if environment_kind == "emulator" and device["ro.kernel.qemu"] != "1":
            raise RuntimeError("模拟器入口拒绝未被识别为模拟器的设备")
        package = "com.github.zzwtsy.chuanmen.p00probe"
        # 核对设备中安装的字节，防止误把旧 APK 的观察值归属到本次构建。
        locations = adb("shell", "pm", "path", package).strip().splitlines()
        if len(locations) != 1 or not locations[0].startswith("package:"):
            raise RuntimeError("实验 APK 未安装或存在 split；请按 network-lab 步骤安装")
        digest = adb("shell", "sha256sum", locations[0][8:]).split()[0]
        report["installed_apk_sha256"] = digest
        if digest != build["apk_sha256"]:
            raise RuntimeError("设备中 APK 与本次构建哈希不一致")
        report["status"] = "failed"
        raw = instrument(package)
        match = re.search(
            r"^INSTRUMENTATION_RESULT: p00_report=(.*)$", raw, re.MULTILINE
        )
        if not match:
            raise RuntimeError("设备没有返回有效平台实验报告")
        observed = json.loads(match.group(1))
        report["observations"] = observed
        rounds = observed.get("rounds", [])
        passed = (
            observed.get("status") == "passed"
            and observed.get("layer") == layer
            and (native or observed.get("native_status") == "unverified")
            and (
                args.native_advanced
                or args.native_address
                or args.native_datapath
                or args.native_revoke
                or args.native_denied
                or len(rounds) == 20
            )
            and all(x.get("status") == "passed" for x in rounds)
            and observed.get("service_destroyed") is True
            and re.search(r"^INSTRUMENTATION_CODE: -1\s*$", raw, re.MULTILINE)
            is not None
        )
        if args.native_boundary:
            passed = (
                passed
                and observed.get("cleanup_error") is None
                and observed.get("callback_on_main") is False
                and len(observed.get("protector_tests", [])) == 3
                and all(
                    x.get("status") == "passed" for x in observed["protector_tests"]
                )
                and all(
                    x.get("closed", {}).get("native_state") == "released"
                    and x["closed"].get("valid_before_owner_close") is True
                    and x["closed"].get("valid_after_owner_close") is False
                    for x in rounds
                )
            )
        if args.native_revoke:
            passed = (
                passed
                and observed.get("revoked") is True
                and observed.get("cleanup_error") is None
                and observed.get("permission_granted_after") is False
                and observed.get("own_active_notifications_after") == 0
                and observed.get("instances_after") == {}
                and observed.get("closed", {}).get("native_state") == "released"
                and observed["closed"].get("valid_before_owner_close") is True
                and observed["closed"].get("valid_after_owner_close") is False
            )
        if args.native_denied:
            passed = (
                passed
                and observed.get("permission_result") == 0
                and observed.get("permission_granted") is False
                and observed.get("instances_after") == {}
                and observed.get("action") == "actual_cancel_button"
            )
        if args.native_address:
            steps = observed.get("steps", [])
            path = args.peer_report.parent / "echo.jsonl"
            echoes = (
                [json.loads(line) for line in path.read_text().splitlines()]
                if path.exists()
                else []
            )
            report["host_echo_records"] = echoes
            report["host_echo_sha256"] = (
                hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None
            )
            passed = (
                passed
                and observed.get("run_id") == peer["run_id"]
                and observed.get("cleanup_error") is None
                and len(steps) == 6
                and len(echoes) == 6
                and observed.get("instances_after") == {}
                and observed.get("own_active_notifications_after") == 0
                and observed.get("late_snapshot", {}).get("accepted") is False
                and all(
                    x.get("status") == "passed"
                    and x["echo"]["request_sha256"] == x["echo"]["response_sha256"]
                    and sum(
                        e.get("protocol") == "tcp"
                        and e.get("request_sha256") == x["echo"]["request_sha256"]
                        for e in echoes
                    )
                    == 1
                    for x in steps
                )
                and len(observed.get("closed_sessions", [])) == 3
                and all(
                    x.get("native_state") == "released"
                    and x.get("valid_before_owner_close") is True
                    and x.get("valid_after_owner_close") is False
                    for x in observed["closed_sessions"]
                )
            )
        if args.native_advanced:
            path = args.peer_report.parent / "echo.jsonl"
            echoes = (
                [json.loads(line) for line in path.read_text().splitlines()]
                if path.exists()
                else []
            )
            report["host_echo_records"] = echoes
            requests = observed.get("requests", [])
            host_rows = report.get("host_advanced_requests", [])
            subnet = observed.get("phone_subnet_echo_records", [])
            exit_rows = [e for e in echoes if e.get("protocol") == "tcp_exit"]
            physical_ips = set()
            for entry in observed.get("network_infos", {}).get("map", {}).values():
                for event in entry.get("events", []):
                    tunnel = (
                        json.loads(event)
                        .get("event", {})
                        .get("PeerConnAdded", {})
                        .get("tunnel", {})
                    )
                    remote = tunnel.get("remote_addr", {}).get("url", "")
                    local = tunnel.get("local_addr", {}).get("url", "")
                    match_ip = re.fullmatch(r"tcp://([0-9.]+):[0-9]+", local)
                    if remote == "tcp://" + peer["host_bind"] and match_ip:
                        physical_ips.add(match_ip[1])
            report["controlled_phone_physical_ips_from_core_tunnel"] = sorted(
                physical_ips
            )
            passed = (
                passed
                and observed.get("run_id") == peer["run_id"]
                and observed.get("cleanup_error") is None
                and len(requests) == 3
                and len(host_rows) == 3
                and len(subnet) == 2
                and len(echoes) == 4
                and all(
                    r.get("status") == "passed"
                    and r["request_sha256"] == r["response_sha256"]
                    for r in requests + host_rows
                )
                and all(
                    sum(e.get("request_sha256") == r["request_sha256"] for e in echoes)
                    == 1
                    for r in requests
                )
                and all(
                    sum(e.get("request_sha256") == r["request_sha256"] for e in subnet)
                    == 1
                    for r in host_rows[:2]
                )
                and len(exit_rows) == 1
                and exit_rows[0]["request_sha256"] == host_rows[2]["request_sha256"]
                and exit_rows[0]["source"][0] in physical_ips
                and observed.get("ports_rebound_after_stop") is True
                and observed.get("portal_udp_bound") is True
                and observed.get("instances_after") == {}
                and observed.get("own_active_notifications_after") == 0
                and observed.get("closed", {}).get("native_state") == "released"
            )
        if args.native_datapath:
            tcp, udp = observed.get("tcp", []), observed.get("udp", [])
            path = args.peer_report.parent / "echo.jsonl"
            echoes = (
                [json.loads(line) for line in path.read_text().splitlines()]
                if path.exists()
                else []
            )
            report["host_echo_records"] = echoes
            report["host_echo_sha256"] = (
                hashlib.sha256(path.read_bytes()).hexdigest() if path.exists() else None
            )
            passed = (
                passed
                and observed.get("run_id") == peer["run_id"]
                and observed.get("cleanup_error") is None
                and len(tcp) == 20
                and len(udp) == 20
                and len(echoes) == 40
                and all(
                    x.get("status") == "passed"
                    and x.get("request_sha256") == x.get("response_sha256")
                    for x in tcp + udp
                )
                and all(
                    sum(
                        e.get("protocol") == proto
                        and e.get("request_sha256") == x.get("request_sha256")
                        for e in echoes
                    )
                    == 1
                    for proto, rows in (("tcp", tcp), ("udp", udp))
                    for x in rows
                )
                and observed.get("closed", {}).get("native_state") == "released"
                and observed["closed"].get("valid_before_owner_close") is True
                and observed["closed"].get("valid_after_owner_close") is False
            )
            if args.connected_cycles:
                passed = (
                    passed
                    and observed.get("connected_cycles") is True
                    and len(rounds) == 20
                    and all(
                        x.get("closed", {}).get("native_state") == "released"
                        and x["closed"].get("valid_before_owner_close") is True
                        and x["closed"].get("valid_after_owner_close") is False
                        and x.get("fd_after", float("inf")) <= observed["baseline_fd"]
                        and x.get("threads_after", float("inf"))
                        <= observed["baseline_threads"]
                        for x in rounds
                    )
                )
        report["status"] = "passed" if passed else "failed"
        report["services_after"] = adb(
            "shell", "dumpsys", "activity", "services", package
        )
    except (
        OSError,
        ValueError,
        KeyError,
        IndexError,
        RuntimeError,
        subprocess.TimeoutExpired,
    ) as error:
        report["error"] = str(error).replace(args.serial, "<controlled-serial>")
        if isinstance(error, subprocess.TimeoutExpired):
            output = error.stdout or ""
            if isinstance(output, bytes):
                output = output.decode(errors="replace")
            report["timeout_output"] = output.replace(
                args.serial, "<controlled-serial>"
            )
        # 失败或超时不能遗留实验会话；只停止本包，绝不操作其他应用或全局调试设置。
        if report["status"] == "failed":
            try:
                adb("shell", "am", "force-stop", "com.github.zzwtsy.chuanmen.p00probe")
                report["services_after"] = adb(
                    "shell",
                    "dumpsys",
                    "activity",
                    "services",
                    "com.github.zzwtsy.chuanmen.p00probe",
                )
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as cleanup_error:
                report["cleanup_error"] = str(cleanup_error).replace(
                    args.serial, "<controlled-serial>"
                )
    with args.output.open("x") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(
        json.dumps(
            {
                "status": report["status"],
                "native_status": "unverified",
                "report": str(args.output),
            },
            ensure_ascii=False,
        )
    )
    return 0 if report["status"] == "passed" else 2


if __name__ == "__main__":
    sys.exit(main())
