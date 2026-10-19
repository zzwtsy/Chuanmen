"""Android 命令实现：显式选择 demo/live，保留稳定退出码和本次实际结果。"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
import re
import shlex
import shutil
import signal
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET
from contextlib import contextmanager
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUFF = "0.17.0"
DEVICE_TEST_PACKAGE = "com.github.zzwtsy.chuanmen.application"
# 设备入口的验收集合；增加共享交互或渲染场景时须同步此契约与开发环境说明。
DEVICE_TEST_CASES = {
    (f"{DEVICE_TEST_PACKAGE}.DemoNavigationTest", name)
    for name in (
        "navigation_preservesInvalidDraft",
        "demoModeAndCopyAction_areVisible",
        "settingsReturn_preservesInvalidDraft",
        "restoreRunningConfiguration_clearsPendingActions",
    )
} | {
    (
        f"{DEVICE_TEST_PACKAGE}.DemoPresentationTest",
        f"presentation_preservesStateAndAccessibleActions[{case}]",
    )
    for case in (
        "empty",
        "disconnected",
        "connected-light",
        "connection-failure",
        "apply-failure",
        "no-samples",
        "connected-dark",
        "connected-large-font",
        "connected-high-contrast",
        "connected-dynamic-color",
    )
}
TERMINATE_GRACE_SECONDS = 5.0
KILL_WAIT_SECONDS = 5.0


class ToolFailure(Exception):
    """可分类的前置缺失、任务失败、设备阻塞、超时或中断。"""

    def __init__(self, code: int, message: str, record: dict | None = None):
        super().__init__(message)
        self.code = code
        self.record = record

    @property
    def status(self) -> str:
        return {
            3: "blocked",
            5: "blocked",
            6: "timed_out",
            130: "interrupted",
            143: "interrupted",
        }.get(self.code, "failed")


class Interruption:
    """信号处理器只记录首次中断，进程所有者在安全边界执行清理。"""

    def __init__(self):
        self.signum: int | None = None

    def receive(self, signum: int, _frame) -> None:
        if self.signum is None:
            self.signum = signum

    def check(self) -> None:
        if self.signum is not None:
            raise ToolFailure(128 + self.signum, f"COMMAND_INTERRUPTED: {self.signum}")


@contextmanager
def cli_signals(interruption: Interruption):
    """只在 CLI 生命周期内接管信号，连续中断不能打断资源收束或报告落盘。"""
    previous = {}
    try:
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous[signum] = signal.signal(signum, interruption.receive)
        yield
    finally:
        for signum, handler in previous.items():
            signal.signal(signum, handler)


class _OwnedProcess:
    """仅收束本次创建的进程组；重复清理复用第一次的结果。"""

    def __init__(self, process: subprocess.Popen):
        self.process = process
        self.scope = "process_group" if os.name == "posix" else "process"
        self.complete: bool | None = None
        self.error: str | None = None
        self.signals: list[int] = []

    def alive(self) -> bool:
        running = self.process.poll() is None
        if self.scope == "process":
            return running
        try:
            os.killpg(self.process.pid, 0)
        except ProcessLookupError:
            return running
        # Linux 上孤儿可能暂留为僵尸；它们已结束，不应被当作仍在运行的资源。
        proc = Path("/proc")
        if os.sys.platform.startswith("linux") and proc.is_dir():
            for entry in proc.iterdir():
                if not entry.name.isdigit():
                    continue
                try:
                    fields = (
                        (entry / "stat")
                        .read_text(errors="replace")
                        .rsplit(")", 1)[1]
                        .split()
                    )
                    if int(fields[2]) == self.process.pid and fields[0] not in (
                        "Z",
                        "X",
                    ):
                        return True
                except (FileNotFoundError, ProcessLookupError):
                    continue
                except PermissionError:
                    # 无权读取成员时保守地继续等待，不把不可观察写成已结束。
                    return True
            return running
        return True

    def _send(self, signum: int, *, force: bool = False) -> None:
        try:
            if self.scope == "process_group":
                os.killpg(self.process.pid, signum)
            elif force:
                self.process.kill()
            else:
                self.process.terminate()
            self.signals.append(signum)
        except ProcessLookupError:
            pass
        except OSError as exc:
            self.error = f"{self.error + '; ' if self.error else ''}{exc}"

    def _wait(self, budget: float) -> bool:
        deadline = time.monotonic() + budget
        while self.alive():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return False
            time.sleep(min(0.05, remaining))
        # poll 已回收直接子进程；这里显式确认退出，不能留下未等待的 Popen。
        self.process.wait(timeout=max(0, deadline - time.monotonic()))
        return True

    def cleanup(self) -> None:
        if self.complete is not None:
            return
        self.complete = False
        try:
            self._send(signal.SIGTERM)
            if self._wait(TERMINATE_GRACE_SECONDS):
                self.complete = True
            else:
                self._send(getattr(signal, "SIGKILL", signal.SIGTERM), force=True)
                self.complete = self._wait(KILL_WAIT_SECONDS)
            if not self.complete:
                detail = "CLEANUP_INCOMPLETE: 超过清理预算，进程仍未确认结束"
                self.error = f"{self.error + '; ' if self.error else ''}{detail}"
        except (OSError, subprocess.TimeoutExpired) as exc:
            self.error = f"CLEANUP_FAILED: {exc}"


def sdk_path(root: Path) -> Path:
    """优先使用有效 sdk.dir，再检查环境变量，与 AGP 的常规 SDK 定位顺序一致。"""
    properties = root / "local.properties"
    if properties.is_file():
        match = re.search(
            r"^[ \t]*sdk\.dir[ \t]*[=:][ \t]*(.+)$",
            properties.read_text(),
            re.MULTILINE,
        )
        if match:
            # SDK Manager 写入的冒号、空格及反斜杠转义不属于路径本身。
            local = Path(re.sub(r"\\(.)", r"\1", match.group(1)))
            local = local if local.is_absolute() else root / local
            if local.is_dir():
                return local
    configured = {}
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = os.environ.get(name)
        if value:
            path = Path(value)
            path = path if path.is_absolute() else root / path
            if path.is_dir():
                configured[name] = path
    if len(configured) == 2 and (
        configured["ANDROID_HOME"].resolve() != configured["ANDROID_SDK_ROOT"].resolve()
    ):
        raise ToolFailure(
            3, "SDK_CONFLICT: ANDROID_HOME 与 ANDROID_SDK_ROOT 必须指向同一 SDK"
        )
    if configured:
        return next(iter(configured.values()))
    raise ToolFailure(
        3,
        "SDK_MISSING: 将 local.properties 的 sdk.dir 或 ANDROID_HOME 指向有效 SDK 目录",
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


def select_live_device(output: str, requested: str | None) -> str:
    """live 显式设备可与模拟器共存；未指定目标时仍要求唯一在线设备。"""
    if requested is None:
        return select_device(output, None)
    devices = {}
    for line in output.splitlines():
        fields = line.split()
        if len(fields) == 2 and fields[0] != "List":
            devices[fields[0]] = fields[1]
    if devices.get(requested) != "device":
        raise ToolFailure(5, "LIVE_NOT_EXECUTED: 指定设备未在线或未授权")
    return requested


def live_ui_ready(power: str, window: str) -> bool:
    """需要可观察的亮屏和未锁定状态；无法核对时不启动依赖 Activity 的用例。"""
    states = re.findall(
        r"\bm(?:InputRestricted|ShowingLockscreen|DreamingLockscreen)=(true|false)\b",
        window,
    )
    return "mWakefulness=Awake" in power and bool(states) and "true" not in states


def live_sources(root: Path) -> dict[str, str]:
    """收集本轮编译和执行输入，不采集个人 SDK 路径、设备标识或运行配置。"""
    names = set()
    for module in ("app", "contracts", "core-bridge", "vpn"):
        for path in (root / module / "src").rglob("*"):
            if path.is_file() and path.relative_to(root).parts[2] in {
                "main",
                "live",
                "liveDebug",
                "androidTestLive",
            }:
                names.add(str(path.relative_to(root)))
    inputs = json.loads((root / "native/inputs.json").read_text())
    names.update(inputs["files"])
    names.update(
        {
            "native/inputs.json",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle/libs.versions.toml",
            "gradle.properties",
            "scripts/native/check_apk.py",
            "scripts/android/android_tools.py",
            "scripts/android/live_contract.py",
        }
    )
    names.update(
        f"{module}/build.gradle.kts"
        for module in ("app", "contracts", "core-bridge", "vpn")
    )
    files = {}
    for name in sorted(names):
        path = root / name
        if (
            Path(name).is_absolute()
            or ".." in Path(name).parts
            or path.is_symlink()
            or not path.resolve().is_relative_to(root.resolve())
        ):
            raise ValueError("设备编译输入引用越界或使用符号链接")
        files[name] = hashlib.sha256(path.read_bytes()).hexdigest()
    return files


class Runner:
    """记录每个尝试，超时或中断时有界收束所属进程，再保留两路输出。"""

    def __init__(
        self,
        root: Path,
        output: Path,
        timeout: float,
        interruption: Interruption | None = None,
    ):
        self.root = root
        self.output = output
        self.timeout = timeout
        self.records: list[dict] = []
        self.interruption = interruption or Interruption()

    def _wait(self, process: subprocess.Popen) -> None:
        deadline = time.monotonic() + self.timeout
        while True:
            self.interruption.check()
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise ToolFailure(6, "COMMAND_TIMEOUT: 超过步骤超时")
            try:
                process.wait(timeout=min(0.1, remaining))
                self.interruption.check()
                return
            except subprocess.TimeoutExpired:
                continue

    def run(self, label: str, argv: list[str], env: dict | None = None) -> str:
        started = time.monotonic()
        log = self.output / f"{label}.log"
        record = {
            "label": label,
            "exit_code": None,
            "elapsed_seconds": 0,
            "log": log.name,
            "status": "passed",
            "timed_out": False,
            "interrupted": False,
            "interruption_signal": None,
            "termination_signal": None,
            "cleanup_scope": "process_group" if os.name == "posix" else "process",
            "cleanup_complete": None,
            "cleanup_signals": [],
            "cleanup_error": None,
            "log_error": None,
            "failure_handled": False,
        }
        self.records.append(record)
        owned = None
        failure = None
        output = ""
        try:
            self.interruption.check()
            executable = argv[0]
            if not (shutil.which(executable) or (self.root / executable).is_file()):
                raise ToolFailure(3, f"TOOL_MISSING: {executable}")
            # 独立文件不受衍生进程持有管道影响；超预算后读取已收到的有界快照。
            with (
                tempfile.TemporaryFile(dir=self.output) as stdout,
                tempfile.TemporaryFile(dir=self.output) as stderr,
            ):
                try:
                    owned = _OwnedProcess(
                        subprocess.Popen(
                            argv,
                            cwd=self.root,
                            env=env,
                            stdout=stdout,
                            stderr=stderr,
                            start_new_session=os.name == "posix",
                        )
                    )
                    self._wait(owned.process)
                    if owned.process.returncode:
                        raise ToolFailure(
                            4, f"TASK_FAILED: {label} 退出码 {owned.process.returncode}"
                        )
                except KeyboardInterrupt:
                    failure = ToolFailure(130, "COMMAND_INTERRUPTED: SIGINT")
                except ToolFailure as exc:
                    failure = exc
                except (OSError, ValueError) as exc:
                    failure = ToolFailure(4, f"COMMAND_FAILED: {label}: {exc}")
                finally:
                    record["timed_out"] = failure is not None and failure.code == 6
                    if owned is not None:
                        if self.interruption.signum is not None:
                            failure = ToolFailure(
                                128 + self.interruption.signum,
                                f"COMMAND_INTERRUPTED: {self.interruption.signum}",
                            )
                        if failure is not None:
                            owned.cleanup()
                        record.update(
                            exit_code=owned.process.poll(),
                            cleanup_complete=owned.complete,
                            cleanup_signals=owned.signals,
                            cleanup_error=owned.error,
                        )
                    for stream in (stdout, stderr):
                        size = os.fstat(stream.fileno()).st_size
                        stream.seek(0)
                        output += stream.read(size).decode(errors="replace")
        except ToolFailure as exc:
            failure = exc
        except (OSError, ValueError) as exc:
            if owned is not None:
                record["log_error"] = f"OUTPUT_READ_FAILED: {exc}"
            failure = failure or ToolFailure(4, f"COMMAND_FAILED: {label}: {exc}")
        except KeyboardInterrupt:
            failure = ToolFailure(130, "COMMAND_INTERRUPTED: SIGINT")
        if self.interruption.signum is not None:
            failure = ToolFailure(
                128 + self.interruption.signum,
                f"COMMAND_INTERRUPTED: {self.interruption.signum}",
            )
        try:
            log.write_text(output, encoding="utf-8")
        except OSError as exc:
            record["log_error"] = str(exc)
            failure = failure or ToolFailure(4, f"LOG_WRITE_FAILED: {label}: {exc}")
        record["elapsed_seconds"] = round(time.monotonic() - started, 3)
        code = record["exit_code"]
        record["termination_signal"] = -code if code is not None and code < 0 else None
        if failure is not None:
            record.update(
                status=failure.status,
                timed_out=record["timed_out"] or failure.code == 6,
                interrupted=failure.code in (130, 143),
                interruption_signal=(failure.code - 128)
                if failure.code in (130, 143)
                else None,
            )
            print(output[-6000:])
            detail = f"{failure}；日志 {log}"
            if record["cleanup_error"]:
                detail += f"；{record['cleanup_error']}"
            if record["log_error"]:
                detail += f"；LOG_WRITE_FAILED: {record['log_error']}"
            raise ToolFailure(failure.code, detail, record) from failure
        print(f"OK: {label}；日志 {log}")
        return output


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
    java_home = os.environ.get("JAVA_HOME")
    java_command = str(Path(java_home) / "bin/java") if java_home else "java"
    java = runner.run("java", [java_command, "-version"])
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
            try:
                print(runner.run(label, argv).strip())
            except ToolFailure as exc:
                record = exc.record
                if (
                    exc.code != 4
                    or record is None
                    or record["exit_code"] in (None, 0)
                    or record["termination_signal"] is not None
                    or record["cleanup_complete"] is False
                    or record["cleanup_error"] is not None
                    or record["log_error"] is not None
                ):
                    raise
                # rustup shim 存在不代表工具链可用；demo 不因此增加原生前置要求。
                record["failure_handled"] = True
                print(f"{argv[0]} 诊断失败（demo 不要求）：{exc}")
        else:
            print(f"{argv[0]} 未安装（demo 不要求）")
    print(
        "demo 无真实核心依赖；P00 production_approved=false 保持冻结，P02 live 核对独立正式产物"
    )
    if native:
        runner.run(
            "native-artifacts",
            ["python3", "scripts/native/build_native.py", "verify-artifacts"],
        )


def check_fast(runner: Runner, offline: bool) -> None:
    native_candidate(runner.root)
    print("NATIVE_CANDIDATE_OK: P00 候选与冻结来源一致；不代替 P02 正式产物检查")
    uv = ["uv", "tool", "run", "--isolated", "--no-python-downloads"]
    if offline:
        uv.append("--offline")
    uv += ["--from", f"ruff=={RUFF}", "ruff"]
    runner.run(
        "ruff-lint", uv + ["check", "--no-cache", "scripts/android", "scripts/native"]
    )
    runner.run(
        "ruff-format",
        uv + ["format", "--no-cache", "--check", "scripts/android", "scripts/native"],
    )
    runner.run("tool-regression", ["python3", "scripts/android/test_android_tools.py"])
    runner.run(
        "live-contract-regression", ["python3", "scripts/android/test_live_contract.py"]
    )
    runner.run(
        "native-tool-regression",
        [
            "python3",
            "-m",
            "unittest",
            "discover",
            "-s",
            "scripts/native",
            "-p",
            "test_*.py",
        ],
    )
    runner.run(
        "module-boundary-regression",
        ["python3", "scripts/android/test_module_boundaries.py"],
    )
    runner.run("boundaries", ["python3", "scripts/android/check_boundaries.py"])
    runner.run("catalog", ["python3", "scripts/catalog/audit_config_catalog.py"])
    gradle(
        runner,
        offline,
        "jvm-tests",
        "--init-script",
        "scripts/android/check_module_boundaries.init.gradle.kts",
        "checkModuleBoundaries",
        ":contracts:test",
        ":core-bridge:test",
        ":vpn:testDebugUnitTest",
        ":app:testDemoDebugUnitTest",
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


def device_test_results(
    directory: Path, expected: set[tuple[str, str]] = DEVICE_TEST_CASES
) -> dict:
    """只接受完整且无失败、错误或跳过的本次 XML，不以退出码或总数代替用例身份。"""
    reports = sorted(directory.rglob("TEST-*.xml"))
    if not reports:
        raise ToolFailure(4, "DEVICE_REPORT_MISSING: 本次设备测试未生成 XML 报告")
    observed: set[tuple[str, str]] = set()
    try:
        for path in reports:
            root = ET.parse(path).getroot()
            if root.tag not in {"testsuite", "testsuites"}:
                raise ValueError("根元素不是 JUnit 测试报告")
            suites = [root] if root.tag == "testsuite" else list(root)
            if not suites or any(suite.tag != "testsuite" for suite in suites):
                raise ValueError("缺少有效 testsuite")
            for suite in suites:
                cases = suite.findall("testcase")
                counts = {
                    key: int(suite.attrib[key])
                    for key in ("tests", "failures", "errors", "skipped")
                }
                if counts["tests"] != len(cases) or any(
                    value < 0 for value in counts.values()
                ):
                    raise ValueError("报告计数与用例数量不一致")
                if any(counts[key] for key in ("failures", "errors", "skipped")):
                    raise ValueError("存在失败、错误或跳过")
                for case in cases:
                    identity = (case.attrib["classname"], case.attrib["name"])
                    if any(
                        case.find(tag) is not None
                        for tag in ("failure", "error", "skipped")
                    ):
                        raise ValueError("用例包含失败、错误或跳过节点")
                    if identity in observed:
                        raise ValueError("重复用例不能充当完整执行")
                    observed.add(identity)
    except (ET.ParseError, ValueError, KeyError) as exc:
        raise ToolFailure(4, f"DEVICE_REPORT_INVALID: {exc}") from exc
    if observed != expected:
        raise ToolFailure(
            4,
            "DEVICE_TESTS_INCOMPLETE: "
            f"缺少 {len(expected - observed)} 项，"
            f"非预期 {len(observed - expected)} 项",
        )
    return {
        "tests": len(observed),
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "cases": [{"class": cls, "name": name} for cls, name in sorted(observed)],
    }


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
    results = (
        runner.root
        / "app/build/outputs/androidTest-results/connected/debug/flavors/demo"
    )
    # 旧报告只移动到忽略的诊断目录，保留失败材料且不让其参与本次成功判断。
    summary_path = runner.output / "device-tests.json"
    if results.exists() or summary_path.exists():
        previous = Path(tempfile.mkdtemp(prefix="previous-results-", dir=runner.output))
        if results.exists():
            results.rename(previous / "demo")
        if summary_path.exists():
            summary_path.rename(previous / "device-tests.json")
    environment = dict(os.environ, ANDROID_SERIAL=selected)
    gradle(
        runner,
        offline,
        "demo-device-tests",
        ":app:connectedDemoDebugAndroidTest",
        "--rerun-tasks",
        f"-Pandroid.testInstrumentationRunnerArguments.package={DEVICE_TEST_PACKAGE}",
        env=environment,
    )
    summary = device_test_results(results)
    write_report(summary_path, summary)
    print(f"DEVICE_TESTS_OK: {summary['tests']} 项完整通过；失败、错误、跳过均为 0")


def verify_live_device(runner: Runner, args: argparse.Namespace) -> None:
    """正式 APK 的显式设备入口；旧 APK、缺对端和缺本次结果均不能宣布通过。"""
    from live_contract import (
        CASES,
        datapath_results,
        dns_results,
        instrumentation_results,
        lifecycle_results,
        network_results,
        peer_input,
        policy_results,
        resource_results,
        revoke_results,
    )

    # 前置失败也隔离旧成功，不能在缺对端或安装拒绝后仍留下似乎属于本次的摘要。
    old = [
        path
        for path in runner.output.iterdir()
        if path.is_file() or path.name == "source-snapshot"
    ]
    if old:
        previous = Path(tempfile.mkdtemp(prefix="previous-results-", dir=runner.output))
        for path in old:
            path.rename(previous / path.name)

    requires_peer = args.live_case in {
        "datapath",
        "resources",
        "dns",
        "policy",
        "network",
    }
    if requires_peer:
        if not all((args.peer_host, args.peer_run_id, args.peer_report)):
            raise ToolFailure(
                3,
                "LIVE_NOT_EXECUTED: 数据通路缺少 --peer-host / --peer-run-id / --peer-report",
            )
        address = ipaddress.IPv4Address(args.peer_host)
        if not address.is_private or address.is_loopback or address.is_unspecified:
            raise ToolFailure(3, "LIVE_NOT_EXECUTED: 受控对端须是明确的私有 LAN IPv4")
        try:
            peer = peer_input(args.peer_report, args.peer_run_id)
            if (
                args.live_case in {"resources", "dns"}
                and peer.get("peer_status_port") != 31274
            ):
                raise ValueError("缺少可核对旧连接与路由的受控对端状态入口")
            if args.live_case == "dns" and (
                peer.get("virtual_dns_port") != 53 or len(args.peer_run_id) > 39
            ):
                raise ValueError("DNS 需要虚拟 53 端口与适合唯一域名标签的运行身份")
            if (
                args.live_case == "network"
                and not ipaddress.IPv6Address(
                    peer.get("physical_ipv6_address", "")
                ).is_global
            ):
                raise ValueError("切网/阻断场景缺少真实全局 IPv6 对端")
        except (OSError, ValueError, KeyError, TypeError) as error:
            raise ToolFailure(3, f"LIVE_NOT_EXECUTED: {error}") from error
    sdk = sdk_path(runner.root)
    adb = shutil.which("adb") or str(sdk / "platform-tools/adb")
    if not Path(adb).is_file():
        raise ToolFailure(3, "ADB_MISSING: 安装官方 platform-tools")
    selected = select_live_device(
        runner.run("device-list", [adb, "devices"]), args.serial
    )
    if args.live_case == "revoke" and not selected.startswith("emulator-"):
        raise ToolFailure(
            5, "LIVE_NOT_EXECUTED: 系统 Forget 用例仅允许本任务测试模拟器"
        )
    api = runner.run(
        "device-api", [adb, "-s", selected, "shell", "getprop", "ro.build.version.sdk"]
    ).strip()
    abi = runner.run(
        "device-abi", [adb, "-s", selected, "shell", "getprop", "ro.product.cpu.abi"]
    ).strip()
    if not api.isdigit() or int(api) < 24 or abi not in {"arm64-v8a", "x86_64"}:
        raise ToolFailure(
            5, "LIVE_NOT_EXECUTED: 当前正式实现只支持 API 24+ / arm64-v8a / x86_64"
        )
    if args.live_case != "bridge":
        power = runner.run(
            "device-screen", [adb, "-s", selected, "shell", "dumpsys", "power"]
        )
        window = runner.run(
            "device-keyguard", [adb, "-s", selected, "shell", "dumpsys", "window"]
        )
        if not live_ui_ready(power, window):
            raise ToolFailure(
                5,
                "LIVE_NOT_EXECUTED: Activity 用例需要手机解锁并保持亮屏；尚未安装或启动测试",
            )
    print(
        f"LIVE_DEVICE: API {api} / {abi}；范围 {args.live_case}，系统授权须正常确认",
        flush=True,
    )
    sources = live_sources(runner.root)
    gradle(
        runner,
        args.offline,
        "assemble-live-device",
        ":app:assembleLiveDebug",
        ":app:assembleLiveDebugAndroidTest",
    )
    apk = runner.root / "app/build/outputs/apk/live/debug/app-live-debug.apk"
    test_apk = (
        runner.root
        / "app/build/outputs/apk/androidTest/live/debug/app-live-debug-androidTest.apk"
    )
    runner.run(
        "live-apk-identity",
        ["python3", "scripts/native/check_apk.py", "--sdk", str(sdk), str(apk)],
    )
    artifacts = {}
    for package, path in (
        ("com.github.zzwtsy.chuanmen", apk),
        ("com.github.zzwtsy.chuanmen.test", test_apk),
    ):
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        artifacts[str(path.relative_to(runner.root))] = digest
        role = "app" if package == "com.github.zzwtsy.chuanmen" else "test"
        packages = runner.run(
            f"installed-{role}-package",
            [adb, "-s", selected, "shell", "pm", "list", "packages", package],
        ).splitlines()
        installed = (
            runner.run(
                f"installed-{role}-apk-path",
                [adb, "-s", selected, "shell", "pm", "path", package],
            ).strip()
            if f"package:{package}" in packages
            else ""
        )
        same = False
        if installed.startswith("package:/") and "\n" not in installed:
            remote = installed.removeprefix("package:")
            remote_hash = runner.run(
                f"installed-{role}-apk-identity",
                [adb, "-s", selected, "shell", "sha256sum " + shlex.quote(remote)],
            ).split()
            same = bool(remote_hash) and remote_hash[0] == digest
        if not same:
            try:
                runner.run(
                    f"install-live-{role}-apk",
                    [adb, "-s", selected, "install", "-r", str(path)],
                )
            except ToolFailure as error:
                raise ToolFailure(
                    5,
                    "LIVE_NOT_EXECUTED: APK 安装失败；请核对本次手机提示和 USB 安装权限",
                    error.record,
                ) from error
    summary_path = runner.output / "device-tests.json"
    if sources != live_sources(runner.root):
        raise ToolFailure(4, "LIVE_INPUT_CHANGED: 构建期间输入变化，本轮不执行设备测试")
    source_path = runner.output / "source-inputs.json"
    snapshot = runner.output / "source-snapshot"
    for name, digest in sources.items():
        data = (runner.root / name).read_bytes()
        if hashlib.sha256(data).hexdigest() != digest:
            raise ToolFailure(
                4, "LIVE_INPUT_CHANGED: 保存输入快照期间源码变化，本轮不执行设备测试"
            )
        target = snapshot / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    write_report(
        source_path,
        {
            "scope": "compile_and_executor_inputs_checked_before_and_after_build_and_device_execution",
            "files": sources,
            "snapshot": "source-snapshot",
            "artifacts": artifacts,
        },
    )
    command = [
        adb,
        "-s",
        selected,
        "shell",
        "am",
        "instrument",
        "-w",
        "-r",
        "-e",
        "class",
        next(iter(CASES[args.live_case]))[0],
    ]
    report_file = {
        "datapath": "p02-datapath-report.json",
        "resources": "p02-resource-report.json",
        "lifecycle": "p02-lifecycle-report.json",
        "dns": "p02-dns-report.json",
        "policy": "p02-policy-report.json",
        "network": "p02-network-report.json",
        "revoke": "p02-revoke-report.json",
    }.get(args.live_case)
    if report_file is not None:
        runner.run(
            "clear-live-report",
            [
                adb,
                "-s",
                selected,
                "shell",
                "run-as",
                "com.github.zzwtsy.chuanmen",
                "rm",
                "-f",
                f"files/{report_file}",
            ],
        )
        if requires_peer:
            command += [
                "-e",
                "peerHost",
                args.peer_host,
                "-e",
                "runId",
                args.peer_run_id,
            ]
            if args.live_case == "network":
                command += ["-e", "peerIpv6", peer["physical_ipv6_address"]]
        launch = runner.run(
            "foreground-live-activity",
            [
                adb,
                "-s",
                selected,
                "shell",
                "am",
                "start",
                "-W",
                "-n",
                "com.github.zzwtsy.chuanmen/com.github.zzwtsy.chuanmen.MainActivity",
            ],
        )
        if "Status: ok" not in launch:
            raise ToolFailure(5, "LIVE_NOT_EXECUTED: 正式 Activity 未确认进入前台")
    command.append(
        "com.github.zzwtsy.chuanmen.test/androidx.test.runner.AndroidJUnitRunner"
    )
    output = runner.run("live-instrumentation", command)
    if sources != live_sources(runner.root) or any(
        hashlib.sha256((runner.root / name).read_bytes()).hexdigest() != digest
        for name, digest in artifacts.items()
    ):
        raise ToolFailure(
            4, "LIVE_INPUT_CHANGED: 设备执行期间输入或 APK 变化，不能绑定成功结论"
        )
    try:
        report = None
        if report_file is not None:
            raw = runner.run(
                "live-app-report",
                [
                    adb,
                    "-s",
                    selected,
                    "exec-out",
                    "run-as",
                    "com.github.zzwtsy.chuanmen",
                    "cat",
                    f"files/{report_file}",
                ],
            )
            report = json.loads(raw)
        summary = instrumentation_results(output, CASES[args.live_case])
        if report is not None:
            if requires_peer:
                records_name = "dns.jsonl" if args.live_case == "dns" else "echo.jsonl"
                echoes = [
                    json.loads(line)
                    for line in (args.peer_report.parent / records_name)
                    .read_text()
                    .splitlines()
                ]
                checker = {
                    "resources": resource_results,
                    "datapath": datapath_results,
                    "dns": dns_results,
                    "policy": policy_results,
                    "network": network_results,
                }[args.live_case]
                summary[args.live_case] = checker(report, echoes, args.peer_run_id)
                summary["peer_report_sha256"] = hashlib.sha256(
                    args.peer_report.read_bytes()
                ).hexdigest()
            else:
                checker = (
                    revoke_results if args.live_case == "revoke" else lifecycle_results
                )
                summary[args.live_case] = checker(report)
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise ToolFailure(4, f"LIVE_REPORT_INVALID: {error}") from error
    summary.update(
        mode="live", scope=args.live_case, api=int(api), abi=abi, artifacts=artifacts
    )
    summary["source_inputs_sha256"] = hashlib.sha256(
        source_path.read_bytes()
    ).hexdigest()
    write_report(summary_path, summary)
    print(f"LIVE_DEVICE_OK: {summary['tests']} 项完整通过；验收范围 {args.live_case}")


def write_report(path: Path, report: dict) -> None:
    """同目录原子替换，写入失败时保留原报告且不遗留半份新报告。"""
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w", encoding="utf-8", dir=path.parent, delete=False
        ) as stream:
            temporary = Path(stream.name)
            json.dump(report, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
        temporary.replace(path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__,
        epilog="前置：Python 3.10+、仓库 Wrapper 与配置要求的 JDK/SDK；check-fast 另需 uv。"
        "报告：build/android/<命令>/；APK：app/build/outputs/apk/demo/debug/。"
        "退出码：0 成功；2 参数错误；3 前置缺失；4 任务失败；5 设备阻塞；6 超时；"
        "130 SIGINT；143 SIGTERM。",
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
        help="doctor 检查 P02 正式双 ABI 产物与当前输入身份",
    )
    parser.add_argument(
        "--serial",
        help="verify-device 核对目标；demo 要求唯一设备，live 可显式选择，标识只留在忽略目录",
    )
    parser.add_argument(
        "--mode",
        choices=("demo", "live"),
        default="demo",
        help="设备模式默认 demo；live 不回退模拟",
    )
    parser.add_argument(
        "--live-case",
        choices=(
            "bridge",
            "datapath",
            "resources",
            "lifecycle",
            "dns",
            "policy",
            "network",
            "revoke",
        ),
        default="datapath",
        help="live 范围：bridge JNI/pipe；lifecycle 正常授权；datapath 双外层业务；resources 50 轮；dns 系统 resolver；policy DHCP/默认路由/恢复；network 实际切网/IPv6 阻断",
    )
    parser.add_argument("--peer-host", help="受控开发主机私有 LAN IPv4")
    parser.add_argument("--peer-run-id", help="本轮受控对端的 p02-* 请求身份")
    parser.add_argument(
        "--peer-report", type=Path, help="本轮受控对端 ready.json / report.json"
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
    if (
        args.mode != "demo"
        or args.peer_host
        or args.peer_run_id
        or args.peer_report
        or args.live_case != "datapath"
    ) and args.command != "verify-device":
        parser.error("live 与对端参数仅供 verify-device")
    if args.mode == "demo" and (
        args.peer_host
        or args.peer_run_id
        or args.peer_report
        or args.live_case != "datapath"
    ):
        parser.error("受控对端与 --live-case 需要 --mode live")
    output = ROOT / "build/android" / args.command
    if args.mode == "live":
        output = output / "live" / args.live_case
    interruption = Interruption()
    runner = Runner(ROOT, output, args.timeout, interruption)
    with cli_signals(interruption):
        failure = None
        try:
            output.mkdir(parents=True, exist_ok=True)
            interruption.check()
            if args.command == "doctor":
                doctor(runner, args.offline, args.native)
            elif args.command == "check-fast":
                check_fast(runner, args.offline)
            elif args.command == "build-demo":
                build_demo(runner, args.offline)
            else:
                if args.mode == "live":
                    verify_live_device(runner, args)
                else:
                    verify_device(runner, args.offline, args.serial)
        except ToolFailure as exc:
            failure = exc
        except (OSError, ValueError, KeyError, TypeError) as exc:
            failure = ToolFailure(4, f"COMMAND_FAILED: {exc}")
        while True:
            if interruption.signum is not None:
                # 即使被中断的进程自行返回 0，也不能宣布命令成功。
                failure = ToolFailure(
                    128 + interruption.signum,
                    f"COMMAND_INTERRUPTED: {interruption.signum}",
                    failure.record if failure is not None else None,
                )
            code = failure.code if failure is not None else 0
            report = {
                "schema_version": 1,
                "command": args.command,
                "exit_code": code,
                "status": failure.status if failure is not None else "passed",
                "failure": {
                    "message": str(failure),
                    "step": failure.record["label"] if failure.record else None,
                }
                if failure is not None
                else None,
                "steps": runner.records,
            }
            try:
                write_report(output / "result.json", report)
            except OSError as exc:
                print(f"REPORT_WRITE_FAILED: {exc}", file=os.sys.stderr)
                return (
                    (128 + interruption.signum) if interruption.signum else (code or 4)
                )
            # 落盘期间首次收到信号时重写一次，保证顶层状态与实际退出码一致。
            if interruption.signum is None or code == 128 + interruption.signum:
                break
        if failure is not None:
            print(str(failure))
        print(f"报告：{output / 'result.json'}；退出码 {code}")
        return code


if __name__ == "__main__":
    raise SystemExit(main())
