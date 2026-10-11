"""在明确授权设备核验真实 JNI 加载和解析；不能代替 VPN 原生门槛。"""

import argparse
import hashlib
import json
import re
import subprocess
import zipfile
from datetime import UTC, datetime
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument(
        "--environment-kind",
        choices=("physical_device", "emulator"),
        default="physical_device",
    )
    parser.add_argument("--build-report", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--mapping-cases", action="store_true", help="执行 APK 内的全字段合成解析输入"
    )
    args = parser.parse_args()
    if args.output.exists():
        parser.error("禁止覆盖已有实验记录")
    build = json.loads(args.build_report.read_text())
    if (
        build.get("status") != "passed"
        or build.get("layer") != "android_native_jni_parser"
    ):
        parser.error("只接受本 JNI 解析探针的成功构建报告")
    apk = Path(build["apk_path"])
    if sha(apk) != build["apk_sha256"]:
        parser.error("APK 哈希不匹配")
    with zipfile.ZipFile(apk) as archive:
        fixture_data = (
            archive.read("assets/config-cases.json") if args.mapping_cases else None
        )
        fixture = json.loads(fixture_data) if fixture_data else None
        for lib in build["libraries"]:
            data = archive.read("lib/" + lib["abi"] + "/libeasytier_android_jni.so")
            if hashlib.sha256(data).hexdigest() != lib["sha256"]:
                parser.error("APK 内上游库哈希不匹配")
    report = {
        "schema_version": 1,
        "layer": "android_native_config_parser"
        if args.mapping_cases
        else "android_native_jni_parser",
        "status": "blocked",
        "recorded_at_utc": datetime.now(UTC).isoformat(),
        "environment_kind": args.environment_kind,
        "device_id": "device-01",
        "core_commit": build["core_commit"],
        "apk_sha256": build["apk_sha256"],
        "build_report_sha256": sha(args.build_report),
        "network_started": False,
        "vpn_acceptance": "unverified",
        "commands": [],
    }
    pkg = "com.github.zzwtsy.chuanmen.p00nativeprobe"

    def adb(*cmd):
        result = subprocess.run(
            ["adb", "-s", args.serial, *cmd],
            check=False,
            text=True,
            timeout=90,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        report["commands"].append(
            {
                "argv": ["adb", "-s", "<controlled-serial>", *cmd],
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
            for key in (
                "ro.build.fingerprint",
                "ro.build.version.sdk",
                "ro.product.cpu.abi",
                "ro.kernel.qemu",
                "ro.product.manufacturer",
                "ro.product.model",
            )
        }
        report["device"] = device
        if (device["ro.kernel.qemu"] == "1") != (args.environment_kind == "emulator"):
            raise RuntimeError("设备类别与声明不一致")
        abi = device["ro.product.cpu.abi"]
        report["native_library_sha256"] = next(
            x["sha256"] for x in build["libraries"] if x["abi"] == abi
        )
        paths = adb("shell", "pm", "path", pkg).strip().splitlines()
        if len(paths) != 1 or not paths[0].startswith("package:"):
            raise RuntimeError("未安装本实验单 APK")
        digest = adb("shell", "sha256sum", paths[0][8:]).split()[0]
        report["installed_apk_sha256"] = digest
        if digest != build["apk_sha256"]:
            raise RuntimeError("设备 APK 哈希不匹配")
        report["status"] = "failed"
        raw = adb(
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            pkg
            + (
                "/.MappingInstrumentation"
                if args.mapping_cases
                else "/.NativeInstrumentation"
            ),
        )
        match = re.search(
            r"^INSTRUMENTATION_RESULT: p00_report=(.*)$", raw, re.MULTILINE
        )
        if not match:
            raise RuntimeError("缺少有效设备报告")
        observed = json.loads(match.group(1))
        report["observations"] = observed
        cases = observed.get("cases", [])
        passed = (
            observed.get("status") == "passed"
            and observed.get("layer") == report["layer"]
            and observed.get("core_commit") == report["core_commit"]
            and observed.get("network_started") is False
            and len(cases) == (len(fixture["cases"]) if fixture else 9)
            and all(x.get("status") == "passed" for x in cases)
            and observed.get("instances_before") == {}
            and observed.get("instances_after") == {}
            and re.search(r"^INSTRUMENTATION_CODE: -1\s*$", raw, re.MULTILINE)
            is not None
        )
        if fixture:
            report["fixture_sha256"] = hashlib.sha256(fixture_data).hexdigest()
            passed = (
                passed
                and observed.get("fixture_sha256") == report["fixture_sha256"]
                and all(
                    actual.get("name") == expected["name"]
                    and actual.get("accepted") == expected["expected_acceptance"]
                    for actual, expected in zip(cases, fixture["cases"])
                )
            )
        report["status"] = "passed" if passed else "failed"
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
    finally:
        if report["status"] == "failed":
            try:
                adb("shell", "am", "force-stop", pkg)
            except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
                report["cleanup_error"] = str(error).replace(
                    args.serial, "<controlled-serial>"
                )
        with args.output.open("x") as stream:
            json.dump(report, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
    print(
        json.dumps(
            {
                "status": report["status"],
                "vpn_acceptance": "unverified",
                "report": str(args.output),
            }
        )
    )
    return 0 if report["status"] == "passed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
