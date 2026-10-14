"""P01 Android 命令实现：只调用已建立的任务，保留稳定退出码与本地报告。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import tempfile
import time
from contextlib import contextmanager
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RUFF = "0.17.0"
DEVICE_TEST_PACKAGE = "com.github.zzwtsy.chuanmen.application"
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
