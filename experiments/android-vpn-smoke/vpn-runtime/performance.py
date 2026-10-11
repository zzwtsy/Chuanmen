"""启动或回收独立设备性能实验；拔 USB 后工作线程继续，不将 ADB 成功当原生结果。"""

import argparse
import hashlib
import json
import subprocess
import time
import zipfile
from datetime import UTC, datetime
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--build-report", type=Path, required=True)
    parser.add_argument("--peer-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--idle-seconds", type=int, default=1800)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--start-only", action="store_true")
    action.add_argument("--collect", action="store_true")
    parser.add_argument("--started-report", type=Path)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("禁止覆盖已有记录")
    if not 300 <= args.idle_seconds <= 3600:
        parser.error("空闲测量限定 300–3600 秒")
    if args.collect and not args.started_report:
        parser.error("回收需要原始启动检查点")
    build = json.loads(args.build_report.read_text())
    peer = json.loads(args.peer_report.read_text())
    if (
        build.get("status") != "passed"
        or build.get("layer") != "android_native_boundary"
        or not peer.get("benchmark")
    ):
        parser.error("需要真实边界 APK 和持续传输对端")
    if sha(Path(build["apk_path"])) != build["apk_sha256"]:
        parser.error("本地 APK 哈希不符")
    run_id = peer["run_id"]
    package = "com.github.zzwtsy.chuanmen.p00probe"
    report = {
        "task": "P00-08",
        "layer": "android_native_performance",
        "status": "blocked",
        "native_status": "unverified",
        "recorded_at_utc": datetime.now(UTC).isoformat(),
        "run_id": run_id,
        "environment_kind": "physical_device",
        "core_commit": build["core_commit"],
        "apk_sha256": build["apk_sha256"],
        "build_report_sha256": sha(args.build_report),
        "peer_report_sha256": sha(args.peer_report),
        "peer_binary_sha256": peer["binary_sha256"],
        "runner_sha256": sha(Path(__file__)),
        "commands": [],
        "device_id": "device-01",
    }

    def adb(*argv):
        result = subprocess.run(
            ["adb", "-s", args.serial, *argv],
            check=False,
            text=True,
            timeout=15,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        report["commands"].append(
            {
                "argv": ["adb", "-s", "<controlled-serial>", *argv],
                "exit_code": result.returncode,
                "output": result.stdout.replace(args.serial, "<controlled-serial>"),
            }
        )
        if result.returncode:
            raise RuntimeError("ADB 命令失败")
        return result.stdout

    try:
        if adb("get-state").strip() != "device":
            raise RuntimeError("指定设备未就绪")
        device = {
            key: adb("shell", "getprop", key).strip()
            for key in [
                "ro.build.fingerprint",
                "ro.build.version.sdk",
                "ro.product.cpu.abi",
                "ro.kernel.qemu",
            ]
        }
        if device["ro.kernel.qemu"] == "1":
            raise RuntimeError("性能主证据必须是真机")
        report["device"] = device
        report["native_library_sha256"] = next(
            x["sha256"]
            for x in build["libraries"]
            if x["abi"] == device["ro.product.cpu.abi"]
        )
        with zipfile.ZipFile(build["apk_path"]) as archive:
            if (
                hashlib.sha256(
                    archive.read(
                        "lib/"
                        + device["ro.product.cpu.abi"]
                        + "/libeasytier_android_jni.so"
                    )
                ).hexdigest()
                != report["native_library_sha256"]
            ):
                raise RuntimeError("APK 内实际原生库哈希不符")
        paths = adb("shell", "pm", "path", package).strip().splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            raise RuntimeError("实验包不存在或非单 APK")
        report["installed_apk_sha256"] = adb(
            "shell", "sha256sum", paths[0][8:]
        ).split()[0]
        if report["installed_apk_sha256"] != build["apk_sha256"]:
            raise RuntimeError("设备 APK 哈希不符")
        filename = "files/p00-performance-" + run_id + ".json"
        if args.start_only:
            if peer.get("status") != "ready":
                raise RuntimeError("对端未就绪")
            adb("shell", "am", "force-stop", package)
            adb(
                "shell",
                "am",
                "start",
                "-n",
                package + "/.BoundaryActivity",
                "--es",
                "p00_performance_host",
                peer["host_bind"].split(":")[0],
                "--es",
                "p00_run_id",
                run_id,
                "--ei",
                "p00_idle_seconds",
                str(args.idle_seconds),
            )
            deadline = time.monotonic() + 25
            observed = None
            while time.monotonic() < deadline:
                # 文件不存在是起始检查点尚未产生；保留每次返回，不当作业务失败或伪造 ready。
                try:
                    observed = json.loads(
                        adb("shell", "run-as", package, "cat", filename)
                    )
                    break
                except (RuntimeError, ValueError):
                    time.sleep(0.5)
            if not observed:
                raise RuntimeError("缺少手机真实启动检查点")
            report["observations"] = observed
            report["status"] = (
                "running"
                if observed.get("run_id") == run_id
                and observed.get("status")
                in [
                    "measuring_tcp",
                    "measuring_udp",
                    "waiting_unplug_and_screen_off",
                    "measuring_idle",
                ]
                else "failed"
            )
        else:
            started = json.loads(args.started_report.read_text())
            if (
                started.get("status") != "running"
                or started.get("run_id") != run_id
                or started.get("apk_sha256") != report["apk_sha256"]
                or started.get("device") != device
                or started.get("core_commit") != report["core_commit"]
                or started.get("peer_report_sha256") != report["peer_report_sha256"]
            ):
                raise RuntimeError("启动检查点与当前设备、APK 或运行编号不符")
            report["started_report_sha256"] = sha(args.started_report)
            observed = json.loads(adb("shell", "run-as", package, "cat", filename))
            report["observations"] = observed
            if observed.get("status") not in ["passed", "failed"]:
                report["status"] = "running"
            else:
                report["status"] = "failed"
                records_path = args.peer_report.parent / "benchmark.jsonl"
                rows = (
                    [json.loads(x) for x in records_path.read_text().splitlines()]
                    if records_path.exists()
                    else []
                )
                report["host_benchmark_records"] = rows
                idle = observed.get("idle", {})
                requested = started.get("observations", {}).get(
                    "idle_seconds_requested"
                )
                idle_samples = idle.get("samples", [])
                report["idle_observation_valid"] = (
                    isinstance(requested, int)
                    and requested >= 300
                    and observed.get("idle_seconds_requested") == requested
                    and idle.get("duration_seconds", 0) >= requested
                    and len(idle_samples) >= 2
                    and idle.get("conditions_stable") is True
                    and all(
                        x.get("plugged") == 0 and x.get("screen_interactive") is False
                        for x in idle_samples
                    )
                )
                good = (
                    observed.get("status") == "passed"
                    and observed.get("run_id") == run_id
                    and len(rows) == 2
                    and observed.get("layer") == "android_native_performance"
                    and observed.get("core_commit") == report["core_commit"]
                    and report["idle_observation_valid"]
                    and observed.get("instances_after") == {}
                    and observed.get("closed", {}).get("native_state") == "released"
                    and observed.get("closed", {}).get("valid_before_owner_close")
                    is True
                    and observed.get("closed", {}).get("valid_after_owner_close")
                    is False
                    and observed.get("service_destroyed") is True
                    and observed.get("own_active_notifications_after") == 0
                    and observed.get("cleanup_error") is None
                )
                for proto in ["tcp", "udp"]:
                    phone = observed.get(proto, {})
                    host = next((r for r in rows if r.get("protocol") == proto), {})
                    good &= (
                        phone.get("duration_seconds", 0) >= 60
                        and phone.get("frames", 0) > 0
                        and phone.get("source_ip") == "10.222.0.2"
                        and host.get("completed") is True
                        and host.get("run_id") == run_id
                        and all(
                            phone.get(key) == host.get(key)
                            for key in [
                                "frames",
                                "bytes",
                                "request_sha256",
                                "response_sha256",
                            ]
                        )
                        and phone.get("request_sha256") == phone.get("response_sha256")
                    )
                echo_path = args.peer_report.parent / "echo.jsonl"
                echoes = (
                    [json.loads(x) for x in echo_path.read_text().splitlines()]
                    if echo_path.exists()
                    else []
                )
                report["host_connect_echo"] = echoes
                good &= len(echoes) == 1 and echoes[0][
                    "request_sha256"
                ] == observed.get("connect_request_sha256")
                report["status"] = "passed" if good else "failed"
                report["energy_status"] = (
                    "counter_observation"
                    if idle.get("energy_mwh", 0) > 0
                    else "estimated_whole_device"
                    if idle.get("energy_mwh_estimate", 0) > 0
                    else "unverified"
                )
                # 恒定或不支持的计数器不能推出零耗电；原始手机值保留，验收状态单独记录。
                if report["energy_status"] == "unverified":
                    report["energy_reason"] = idle.get(
                        "energy_reason", "计数器没有可分辨下降，不能报告零耗电"
                    )
        report["services_after"] = adb(
            "shell", "dumpsys", "activity", "services", package
        )
    except (
        OSError,
        ValueError,
        KeyError,
        IndexError,
        StopIteration,
        RuntimeError,
        subprocess.TimeoutExpired,
    ) as error:
        report["error"] = str(error).replace(args.serial, "<controlled-serial>")
    with args.output.open("x") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(
        json.dumps(
            {
                "status": report["status"],
                "device_phase": report.get("observations", {}).get("status"),
                "output": str(args.output),
            }
        )
    )
    return 0 if report["status"] in ["running", "passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
