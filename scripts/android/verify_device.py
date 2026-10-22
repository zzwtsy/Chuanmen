"""真实 Android 设备回归：显式选择目标，核对本次 APK、运行结果和受控对端。"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
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


def select_device(output: str, requested: str | None) -> str:
    """未显式指定目标时要求唯一在线设备，避免误安装。"""
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


def select_target(output: str, requested: str | None) -> str:
    """显式设备可与模拟器共存；未指定目标时要求唯一在线设备。"""
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


def ui_ready(power: str, window: str) -> bool:
    """需要可观察的亮屏和未锁定状态；无法核对时不启动依赖 Activity 的用例。"""
    states = re.findall(
        r"\bm(?:InputRestricted|ShowingLockscreen|DreamingLockscreen)=(true|false)\b",
        window,
    )
    return "mWakefulness=Awake" in power and bool(states) and "true" not in states


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

    def run(
        self,
        label: str,
        argv: list[str],
        env: dict | None = None,
        *,
        stdout_file: Path | None = None,
    ) -> str:
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
                    if stdout_file is not None:
                        stdout.seek(0)
                        with stdout_file.open("wb") as destination:
                            shutil.copyfileobj(stdout, destination)
                    for stream in (
                        (stdout, stderr) if stdout_file is None else (stderr,)
                    ):
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


def installed_apk_matches(
    runner: Runner, adb: str, serial: str, remote: str, expected: str, role: str
) -> bool:
    """经应用 UID 读取设备 APK 字节；不依赖远端 sha256sum 或旧 adbd 的 sync 权限。"""
    with tempfile.TemporaryDirectory(
        prefix="installed-", dir=runner.output
    ) as directory:
        local = Path(directory) / "base.apk"
        runner.run(
            f"installed-{role}-apk-identity",
            [
                adb,
                "-s",
                serial,
                "exec-out",
                "run-as",
                "com.github.zzwtsy.chuanmen" + (".test" if role == "test" else ""),
                "cat",
                remote,
            ],
            stdout_file=local,
        )
        return hashlib.sha256(local.read_bytes()).hexdigest() == expected


def gradle(
    runner: Runner, offline: bool, label: str, *tasks: str, env: dict | None = None
) -> str:
    argv = ["./gradlew", "--console=plain"]
    if offline:
        argv.append("--offline")
    return runner.run(label, argv + list(tasks), env)


def verify_device(runner: Runner, args: argparse.Namespace) -> None:
    """正式 APK 的显式设备入口；旧 APK、缺对端和缺本次结果均不能宣布通过。"""
    from device_contracts import (
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

    requires_peer = args.case in {
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
                args.case in {"resources", "dns"}
                and peer.get("peer_status_port") != 31274
            ):
                raise ValueError("缺少可核对旧连接与路由的受控对端状态入口")
            if args.case == "dns" and (
                peer.get("virtual_dns_port") != 53 or len(args.peer_run_id) > 39
            ):
                raise ValueError("DNS 需要虚拟 53 端口与适合唯一域名标签的运行身份")
            if (
                args.case == "network"
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
    selected = select_target(runner.run("device-list", [adb, "devices"]), args.serial)
    if args.case == "revoke" and not selected.startswith("emulator-"):
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
    if args.case != "bridge":
        power = runner.run(
            "device-screen", [adb, "-s", selected, "shell", "dumpsys", "power"]
        )
        window = runner.run(
            "device-keyguard", [adb, "-s", selected, "shell", "dumpsys", "window"]
        )
        if not ui_ready(power, window):
            raise ToolFailure(
                5,
                "LIVE_NOT_EXECUTED: Activity 用例需要手机解锁并保持亮屏；尚未安装或启动测试",
            )
    print(
        f"LIVE_DEVICE: API {api} / {abi}；范围 {args.case}，系统授权须正常确认",
        flush=True,
    )
    gradle(
        runner,
        args.offline,
        "assemble-live-device",
        ":app:assembleDebug",
        ":app:assembleDebugAndroidTest",
    )
    apk = runner.root / "app/build/outputs/apk/debug/app-debug.apk"
    test_apk = (
        runner.root
        / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
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
            same = installed_apk_matches(runner, adb, selected, remote, digest, role)
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
        next(iter(CASES[args.case]))[0],
    ]
    report_file = {
        "datapath": "p02-datapath-report.json",
        "resources": "p02-resource-report.json",
        "lifecycle": "p02-lifecycle-report.json",
        "dns": "p02-dns-report.json",
        "policy": "p02-policy-report.json",
        "network": "p02-network-report.json",
        "revoke": "p02-revoke-report.json",
    }.get(args.case)
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
            if args.case == "network":
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
    if any(
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
        summary = instrumentation_results(output, CASES[args.case])
        if report is not None:
            if requires_peer:
                records_names = ["dns.jsonl" if args.case == "dns" else "echo.jsonl"]
                if args.case == "network":
                    records_names.append("physical-echo.jsonl")
                echoes = [
                    json.loads(line)
                    for name in records_names
                    for line in (args.peer_report.parent / name)
                    .read_text()
                    .splitlines()
                ]
                checker = {
                    "resources": resource_results,
                    "datapath": datapath_results,
                    "dns": dns_results,
                    "policy": policy_results,
                    "network": network_results,
                }[args.case]
                summary[args.case] = checker(report, echoes, args.peer_run_id)
                summary["peer_report_sha256"] = hashlib.sha256(
                    args.peer_report.read_bytes()
                ).hexdigest()
            else:
                checker = revoke_results if args.case == "revoke" else lifecycle_results
                summary[args.case] = checker(report)
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise ToolFailure(4, f"LIVE_REPORT_INVALID: {error}") from error
    summary.update(scope=args.case, api=int(api), abi=abi, artifacts=artifacts)
    write_report(summary_path, summary)
    print(f"LIVE_DEVICE_OK: {summary['tests']} 项完整通过；验收范围 {args.case}")


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
        epilog="输出 app/build/device-tests/<case>/run-*/；退出码：0 成功，2 参数错误，"
        "3 前置缺失，4 失败，5 设备阻塞，6 超时，130 SIGINT，143 SIGTERM。",
    )
    parser.add_argument(
        "--case",
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
        required=True,
    )
    parser.add_argument(
        "--offline",
        action="store_true",
        help="Gradle 不下载依赖，需已准备 native 和缓存",
    )
    parser.add_argument("--serial", help="明确选择已授权设备；只写忽略目录")
    parser.add_argument("--peer-host", help="受控开发主机的私有 LAN IPv4")
    parser.add_argument("--peer-run-id", help="本轮受控对端的 p02-* 请求身份")
    parser.add_argument(
        "--peer-report", type=Path, help="本轮对端 ready.json / report.json"
    )
    parser.add_argument("--timeout", type=int, default=600, help="每条子命令超时秒数")
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout 必须为正整数")
    # 每次使用新目录，前置失败或测试缺报不会复用旧成功。
    output = ROOT / "app/build/device-tests" / args.case / f"run-{time.time_ns()}"
    interruption = Interruption()
    runner = Runner(ROOT, output, args.timeout, interruption)
    with cli_signals(interruption):
        failure = None
        try:
            output.mkdir(parents=True, exist_ok=False)
            interruption.check()
            verify_device(runner, args)
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
                "command": "verify-device",
                "scope": args.case,
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
