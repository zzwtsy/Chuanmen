"""真实断网实验：启动本包 Activity 工作线程，跨无线 ADB 中断读取私有最终报告。"""

import argparse
import hashlib
import json
import re
import subprocess
import time
from datetime import UTC, datetime
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--build-report", type=Path, required=True)
    parser.add_argument("--peer-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--resume-ready",
        type=Path,
        help="只采集同一次运行；引用断网前已保存的 ready 检查点",
    )
    args = parser.parse_args()
    if args.output.exists():
        parser.error("禁止覆盖实验结果")
    build, peer = (
        json.loads(args.build_report.read_text()),
        json.loads(args.peer_report.read_text()),
    )
    if (
        build.get("status") != "passed"
        or build.get("layer") != "android_native_boundary"
        or peer.get("status") != "ready"
    ):
        parser.error("实验 APK 或独立对端未就绪")
    if build.get("core_commit") != peer.get("core_commit"):
        parser.error("两端核心 SHA 不一致")
    if (
        hashlib.sha256(Path(build["apk_path"]).read_bytes()).hexdigest()
        != build["apk_sha256"]
    ):
        parser.error("APK 哈希不一致")
    package = "com.github.zzwtsy.chuanmen.p00probe"
    report = {
        "layer": "android_native_offline",
        "status": "blocked",
        "native_status": "unverified",
        "environment_kind": "physical_device",
        "recorded_at_utc": datetime.now(UTC).isoformat(),
        "core_commit": build["core_commit"],
        "apk_sha256": build["apk_sha256"],
        "libraries": build["libraries"],
        "build_report_sha256": hashlib.sha256(
            args.build_report.read_bytes()
        ).hexdigest(),
        "peer_ready_report_sha256": hashlib.sha256(
            args.peer_report.read_bytes()
        ).hexdigest(),
        "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "run_id": peer["run_id"],
        "commands": [],
    }
    serial = args.serial

    def adb(*command, recorded=True):
        result = subprocess.run(
            ["adb", "-s", serial, *command],
            text=True,
            capture_output=True,
            timeout=10,
            check=False,
        )
        if recorded:
            report["commands"].append(
                {
                    "argv": ["adb", "-s", "<controlled-serial>", *command],
                    "exit_code": result.returncode,
                    "output": result.stdout.replace(serial, "<controlled-serial>"),
                    "error": result.stderr.replace(serial, "<controlled-serial>"),
                }
            )
        return result

    try:
        fingerprint = adb("shell", "getprop", "ro.build.fingerprint").stdout.strip()
        if (
            not fingerprint
            or adb("shell", "getprop", "ro.kernel.qemu").stdout.strip() == "1"
        ):
            raise RuntimeError("要求指定真机")
        report["device_fingerprint"] = fingerprint
        location = adb("shell", "pm", "path", package).stdout.strip()
        if not location.startswith("package:") or "\n" in location:
            raise RuntimeError("实验 APK 未安装或为 split")
        if (
            adb("shell", "sha256sum", location[8:]).stdout.split()[0]
            != build["apk_sha256"]
        ):
            raise RuntimeError("安装 APK 不匹配")
        if (
            "ACTIVATE_VPN: allow"
            not in adb("shell", "appops", "get", package, "ACTIVATE_VPN").stdout
        ):
            raise RuntimeError("请先通过真实系统对话框授权，不自动更改 AppOps")
        if args.resume_ready:
            prior = json.loads(args.resume_ready.read_text())
            if (
                prior.get("run_id") != peer["run_id"]
                or prior.get("apk_sha256") != build["apk_sha256"]
                or prior.get("device_fingerprint") != fingerprint
                or prior.get("peer_ready_report_sha256")
                != report["peer_ready_report_sha256"]
                or prior.get("ready_observations", {}).get("status")
                != "waiting_offline"
            ):
                raise RuntimeError("恢复采集检查点与本次设备、APK 或对端不匹配")
            report["resume_ready"] = {
                "path": str(args.resume_ready),
                "sha256": hashlib.sha256(args.resume_ready.read_bytes()).hexdigest(),
            }
            report["ready_observations"] = prior["ready_observations"]
        else:
            adb("shell", "am", "force-stop", package)
            launched = adb(
                "shell",
                "am",
                "start",
                "-n",
                package + "/.BoundaryActivity",
                "--es",
                "p00_offline_host",
                peer["host_bind"].split(":")[0],
                "--es",
                "p00_run_id",
                peer["run_id"],
            )
            if launched.returncode:
                raise RuntimeError("实验 Activity 启动失败")
        deadline = time.monotonic() + 240
        notified = bool(args.resume_ready)
        while time.monotonic() < deadline:
            try:
                result = adb(
                    "shell",
                    "run-as",
                    package,
                    "cat",
                    "files/p00-offline.json",
                    recorded=False,
                )
            except subprocess.TimeoutExpired:
                # 无线 ADB 的失联超时只触发重连，不覆盖手机上的真实实验结果。
                result = subprocess.CompletedProcess(
                    [], 1, "", "controlled ADB read timed out"
                )
            try:
                observed = json.loads(result.stdout)
            except ValueError:
                observed = None
            if observed and observed.get("run_id") == peer["run_id"]:
                if observed.get("status") == "waiting_offline" and not notified:
                    report["ready_observations"] = observed
                    args.output.with_suffix(".ready.json").write_text(
                        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
                    )
                    print(
                        json.dumps(
                            {
                                "phase": "waiting_offline",
                                "action": "关闭 Wi-Fi 和移动数据至少 10 秒，再恢复 Wi-Fi 和无线调试",
                            },
                            ensure_ascii=False,
                        ),
                        flush=True,
                    )
                    notified = True
                elif observed.get("status") in ("passed", "failed"):
                    report["observations"] = observed
                    break
            if result.returncode and notified:
                report["adb_disconnect_observed"] = True
                # 仅允许同一已配对 mDNS 身份自动重连；不连接其他设备或扫描端口。
                stem = args.serial.split("._adb-tls-connect")[0].split(" (")[0]
                if stem.startswith("adb-"):
                    devices = subprocess.run(
                        ["adb", "devices", "-l"],
                        check=False,
                        capture_output=True,
                        text=True,
                        timeout=10,
                    )
                    for line in devices.stdout.splitlines():
                        match = re.match(r"^(.+?)\s+device\s+", line)
                        if match and match[1].startswith(stem):
                            candidate = match[1]
                            try:
                                check = subprocess.run(
                                    [
                                        "adb",
                                        "-s",
                                        candidate,
                                        "shell",
                                        "getprop",
                                        "ro.build.fingerprint",
                                    ],
                                    check=False,
                                    capture_output=True,
                                    text=True,
                                    timeout=10,
                                )
                            except subprocess.TimeoutExpired:
                                continue
                            if (
                                check.returncode == 0
                                and check.stdout.strip() == fingerprint
                            ):
                                serial = candidate
                                break
            time.sleep(0.5)
        else:
            raise RuntimeError("未取得真实断网最终结果；不要将此前人工断网计作通过")
        observed = report["observations"]
        echoes_path = args.peer_report.parent / "echo.jsonl"
        echoes = (
            [json.loads(line) for line in echoes_path.read_text().splitlines()]
            if echoes_path.exists()
            else []
        )
        report["host_echo_records"] = echoes
        closed = observed.get("closed", {})
        passed = (
            observed.get("status") == "passed"
            and observed.get("network_loss_observed") is True
            and observed.get("physical_networks_before", 0) > 0
            and observed.get("physical_networks_after") == 0
            and bool(observed.get("offline_connect_error"))
            and observed.get("instances_after") == {}
            and observed.get("service_destroyed") is True
            and observed.get("cleanup_error") is None
            and observed.get("own_active_notifications_after") == 0
            and closed.get("native_state") == "released"
            and closed.get("valid_before_owner_close") is True
            and closed.get("valid_after_owner_close") is False
            and len(echoes) == 1
            and echoes[0]["request_sha256"] == observed.get("before_request_sha256")
        )
        report["status"] = "passed" if passed else "failed"
        report["services_after"] = adb(
            "shell", "dumpsys", "activity", "services", package
        ).stdout
    except (
        OSError,
        ValueError,
        KeyError,
        IndexError,
        RuntimeError,
        subprocess.TimeoutExpired,
    ) as error:
        report["error"] = str(error).replace(args.serial, "<controlled-serial>")
    report["device_id"] = "device-01"
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(
        json.dumps(
            {"status": report["status"], "report": str(args.output)}, ensure_ascii=False
        )
    )
    return 0 if report["status"] == "passed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
