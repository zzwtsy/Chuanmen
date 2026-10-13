"""P01 Android 命令实现：只调用已建立的任务，保留稳定退出码与本地报告。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUFF = "0.17.0"
DEVICE_TEST_PACKAGE = "com.github.zzwtsy.chuanmen.application"


class ToolFailure(Exception):
    """可分类的前置缺失、任务失败、设备阻塞或超时。"""

    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code


def sdk_path(root: Path) -> Path:
    """只读取 SDK 定位项，不访问签名、凭据或其他个人配置。"""
    configured = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if configured:
        return Path(configured)
    properties = root / "local.properties"
    if properties.exists():
        match = re.search(r"^sdk\.dir=(.+)$", properties.read_text(), re.MULTILINE)
        if match:
            return Path(match.group(1).replace("\\:", ":").replace("\\\\", "\\"))
    raise ToolFailure(
        3, "SDK_MISSING: 设置 ANDROID_HOME 或 local.properties 的 sdk.dir"
    )


def native_candidate(root: Path) -> dict:
    """核对候选输入与冻结来源；候选记录不能赋予生产批准或解锁 live。"""
    manifest = root / "gradle/native-candidate.json"
    if not manifest.is_file():
        raise ToolFailure(3, "NATIVE_CANDIDATE_MISSING: 缺少候选输入清单")
    try:
        data = json.loads(manifest.read_text())
        if (
            data["schema_version"] != 1
            or data["status"] != "candidate"
            or data["production_approved"] is not False
            or not re.fullmatch(r"[0-9a-f]{40}", data["upstream"]["commit"])
        ):
            raise ValueError("候选身份或生产批准字段不符合约定")
        expected_inputs = {
            "core_cargo_lock",
            "patch_record",
            "patch_diff",
            "reference_build",
        }
        if set(data["inputs"]) != expected_inputs:
            raise ValueError("候选来源清单不完整")
        contents = {}
        for name, item in data["inputs"].items():
            path = root / item["path"]
            if not path.resolve().is_relative_to(root.resolve()):
                raise ValueError("输入路径超出仓库")
            content = path.read_bytes()
            if hashlib.sha256(content).hexdigest() != item["sha256"]:
                raise ValueError(f"{name} 的来源摘要不匹配")
            contents[name] = content
        patch = json.loads(contents["patch_record"])
        reference = json.loads(contents["reference_build"])
        lock_hash = data["inputs"]["core_cargo_lock"]["sha256"]
        toolchain = data["toolchain"]
        rust = reference["tool_versions"]["rustc"]
        if (
            patch["production_approved"] is not False
            or patch["core_commit"] != data["upstream"]["commit"]
            or reference["candidate_commit"] != data["upstream"]["commit"]
            or patch["lockfile_sha256"] != lock_hash
            or reference["cargo_lock_sha256"] != lock_hash
            or reference["ndk_revision"] != toolchain["ndk_revision"]
            or reference["abis"] != toolchain["abis"]
            or f"release: {toolchain['rust_version']}\n" not in rust
            or f"commit-hash: {toolchain['rust_commit']}\n" not in rust
        ):
            raise ValueError("候选工具链、核心或补丁与冻结来源不一致")
    except (OSError, ValueError, KeyError, TypeError) as exc:
        raise ToolFailure(4, f"NATIVE_CANDIDATE_INVALID: {exc}") from exc
    return data


def select_device(output: str, requested: str | None) -> str:
    """P01 只在唯一已授权设备上测试，避免 Gradle 对其他已连接设备安装 APK。"""
    devices = [line.split() for line in output.splitlines() if "\t" in line]
    connected = [
        parts[0] for parts in devices if len(parts) >= 2 and parts[1] == "device"
    ]
    if len(connected) != 1:
        raise ToolFailure(
            5,
            "DEVICE_BLOCKED: 需要且仅允许一个已授权在线设备；没有设备或多设备均不执行安装",
        )
    if requested is not None and requested != connected[0]:
        raise ToolFailure(5, "DEVICE_BLOCKED: 指定设备未在线或未授权")
    return connected[0]


class Runner:
    """对子命令设置超时；完整输出留在忽略目录，终端保留失败分类和日志定位。"""

    def __init__(self, root: Path, output: Path, timeout: int):
        self.root = root
        self.output = output
        self.timeout = timeout
        self.records: list[dict] = []

    def run(self, label: str, argv: list[str], env: dict | None = None) -> str:
        executable = argv[0]
        if not (shutil.which(executable) or (self.root / executable).is_file()):
            raise ToolFailure(3, f"TOOL_MISSING: {executable}")
        started = time.monotonic()
        log = self.output / f"{label}.log"
        try:
            result = subprocess.run(
                argv,
                cwd=self.root,
                env=env,
                capture_output=True,
                text=True,
                timeout=self.timeout,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            partial = exc.stdout or b""
            log.write_text(
                partial.decode(errors="replace")
                if isinstance(partial, bytes)
                else partial
            )
            raise ToolFailure(6, f"COMMAND_TIMEOUT: {label}；日志 {log}") from exc
        log.write_text(result.stdout + result.stderr)
        self.records.append(
            {
                "label": label,
                "exit_code": result.returncode,
                "elapsed_seconds": round(time.monotonic() - started, 3),
                "log": log.name,
            }
        )
        if result.returncode:
            print((result.stdout + result.stderr)[-6000:])
            raise ToolFailure(
                4, f"TASK_FAILED: {label} 退出码 {result.returncode}；日志 {log}"
            )
        print(f"OK: {label}；日志 {log}")
        return result.stdout + result.stderr


def gradle(
    runner: Runner, offline: bool, label: str, *tasks: str, env: dict | None = None
) -> str:
    argv = ["./gradlew", "--console=plain"]
    if offline:
        argv.append("--offline")
    return runner.run(label, argv + list(tasks), env)


def doctor(runner: Runner, offline: bool, native: bool) -> None:
    candidate = native_candidate(runner.root)
    toolchain = candidate["toolchain"]
    print(
        f"候选核心 {candidate['upstream']['commit']}；NDK {toolchain['ndk_revision']}；"
        f"Rust {toolchain['rust_version']}；ABI {', '.join(toolchain['abis'])}；生产未批准"
    )
    java = runner.run("java", ["java", "-version"])
    print(java.strip())
    print(f"Python {os.sys.version.split()[0]}")
    print(runner.run("uv", ["uv", "--version"]).strip())
    print(gradle(runner, offline, "gradle-version", "--version").strip())
    sdk = sdk_path(runner.root)
    app_build = (runner.root / "app/build.gradle.kts").read_text()
    compile_sdk = re.search(r"version\s*=\s*release\((\d+)\)", app_build)
    if not compile_sdk:
        raise ToolFailure(3, "SDK_DECLARATION_MISSING: 无法读取实际 compileSdk")
    api = compile_sdk.group(1)
    platforms = list((sdk / "platforms").glob(f"android-{api}*"))
    if not any((path / "android.jar").is_file() for path in platforms):
        raise ToolFailure(
            3, f"SDK_PLATFORM_MISSING: 安装官方 Android SDK Platform {api}"
        )
    build_tools = list((sdk / "build-tools").glob("*/source.properties"))
    declared_tools = re.search(r'buildToolsVersion\s*=\s*"([^"]+)"', app_build)
    if not declared_tools:
        raise ToolFailure(
            3, "BUILD_TOOLS_DECLARATION_MISSING: 无法读取固定构建工具版本"
        )
    required_tools = declared_tools.group(1)
    if not (sdk / "build-tools" / required_tools / "source.properties").is_file():
        raise ToolFailure(
            3,
            f"BUILD_TOOLS_MISSING: 通过官方 SDK Manager 安装 build-tools {required_tools}",
        )
    print(
        f"SDK Platform {api}；build-tools: {', '.join(p.parent.name for p in build_tools)}"
    )
    ndks = list((sdk / "ndk").glob("*/source.properties"))
    print(
        f"已安装 NDK: {', '.join(p.parent.name for p in ndks) or '未安装'}（demo 不要求 NDK）"
    )
    for label, argv in (
        ("rust", ["rustc", "--version"]),
        ("rust-targets", ["rustup", "target", "list", "--installed"]),
    ):
        if shutil.which(argv[0]):
            print(runner.run(label, argv).strip())
        else:
            print(f"{argv[0]} 未安装（demo 不要求）")
    print(
        "demo 无真实核心依赖；live 阻断原因：正式服务/桥接未接入，P00 补丁 production_approved=false"
    )
    if native:
        raise ToolFailure(
            3,
            "NATIVE_NOT_READY: 候选输入已固定，P02 的正式服务/桥接与生产补丁仍未批准",
        )


def check_fast(runner: Runner, offline: bool) -> None:
    native_candidate(runner.root)
    print("NATIVE_CANDIDATE_OK: 候选输入与冻结来源一致；live 仍阻断")
    uv = ["uv", "tool", "run", "--isolated", "--no-python-downloads"]
    if offline:
        uv.append("--offline")
    uv += ["--from", f"ruff=={RUFF}", "ruff"]
    runner.run("ruff-lint", uv + ["check", "--no-cache", "scripts/android"])
    runner.run(
        "ruff-format", uv + ["format", "--no-cache", "--check", "scripts/android"]
    )
    runner.run("tool-regression", ["python3", "scripts/android/test_android_tools.py"])
    runner.run("boundaries", ["python3", "scripts/android/check_boundaries.py"])
    runner.run("catalog", ["python3", "scripts/catalog/audit_config_catalog.py"])
    gradle(
        runner, offline, "jvm-tests", ":contracts:test", ":app:testDemoDebugUnitTest"
    )


def build_demo(runner: Runner, offline: bool) -> None:
    gradle(runner, offline, "assemble-demo", ":app:assembleDemoDebug")
    output = runner.root / "app/build/outputs/apk/demo/debug"
    metadata_path = output / "output-metadata.json"
    if not metadata_path.is_file():
        raise ToolFailure(4, "APK_MISSING: Gradle 未生成 demoDebug 的输出元数据")
    metadata = json.loads(metadata_path.read_text())
    if (
        metadata.get("applicationId") != "com.github.zzwtsy.chuanmen.demo"
        or metadata.get("variantName") != "demoDebug"
    ):
        raise ToolFailure(
            4, "APK_VARIANT_MISMATCH: 产物必须使用独立 demo 包名与 demoDebug 变体"
        )
    for element in metadata["elements"]:
        apk = output / element["outputFile"]
        if not apk.is_file():
            raise ToolFailure(4, "APK_MISSING: 输出元数据指向不存在的 APK")
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        print(f"APK: {apk.relative_to(runner.root)}\nSHA256: {digest}")


def verify_device(runner: Runner, offline: bool, serial: str | None) -> None:
    adb = shutil.which("adb")
    if not adb:
        candidate = sdk_path(runner.root) / "platform-tools/adb"
        if candidate.is_file():
            adb = str(candidate)
        else:
            raise ToolFailure(3, "ADB_MISSING: 安装官方 platform-tools")
    selected = select_device(runner.run("device-list", [adb, "devices"]), serial)
    api = runner.run(
        "device-api", [adb, "-s", selected, "shell", "getprop", "ro.build.version.sdk"]
    ).strip()
    if not api.isdigit() or int(api) < 24:
        raise ToolFailure(5, "DEVICE_BLOCKED: 设备 API 未知或低于当前 minSdk")
    print(f"唯一设备 API {api}；仅安装 demo 并执行 Compose 测试，不代表真实 VPN 验证")
    environment = dict(os.environ, ANDROID_SERIAL=selected)
    gradle(
        runner,
        offline,
        "demo-device-tests",
        ":app:connectedDemoDebugAndroidTest",
        f"-Pandroid.testInstrumentationRunnerArguments.package={DEVICE_TEST_PACKAGE}",
        env=environment,
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__,
        epilog="前置：Python 3.10+、仓库 Wrapper 与配置要求的 JDK/SDK；check-fast 另需 uv。"
        "报告：build/android/<命令>/；APK：app/build/outputs/apk/demo/debug/。"
        "退出码：0 成功；2 参数错误；3 前置缺失；4 任务失败；5 设备阻塞；6 超时。",
    )
    parser.add_argument(
        "command", choices=("doctor", "check-fast", "build-demo", "verify-device")
    )
    parser.add_argument(
        "--offline", action="store_true", help="Gradle 和 uv 不访问网络；需已有依赖缓存"
    )
    parser.add_argument(
        "--native",
        action="store_true",
        help="doctor 检查正式 native 门槛；P01 会明确失败",
    )
    parser.add_argument(
        "--serial", help="verify-device 指定唯一在线设备，序列号只留在忽略目录"
    )
    parser.add_argument(
        "--timeout", type=int, default=600, help="每条子命令的超时秒数，默认 600"
    )
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout 必须为正整数")
    if args.native and args.command != "doctor":
        parser.error("--native 仅供 doctor")
    if args.serial and args.command != "verify-device":
        parser.error("--serial 仅供 verify-device")
    output = ROOT / "build/android" / args.command
    output.mkdir(parents=True, exist_ok=True)
    runner = Runner(ROOT, output, args.timeout)
    code = 0
    try:
        if args.command == "doctor":
            doctor(runner, args.offline, args.native)
        elif args.command == "check-fast":
            check_fast(runner, args.offline)
        elif args.command == "build-demo":
            build_demo(runner, args.offline)
        else:
            verify_device(runner, args.offline, args.serial)
    except ToolFailure as exc:
        print(str(exc))
        code = exc.code
    (output / "result.json").write_text(
        json.dumps(
            {"command": args.command, "exit_code": code, "steps": runner.records},
            ensure_ascii=False,
            indent=2,
        )
        + "\n"
    )
    print(f"报告：{output / 'result.json'}；退出码 {code}")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
