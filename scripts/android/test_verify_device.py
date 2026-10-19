"""设备回归工具的进程、信号和报告契约；不启动 Gradle、ADB 或真实设备。"""

import hashlib
import io
import json
import os
import signal
import subprocess
import sys
import time
import unittest
from contextlib import contextmanager, redirect_stderr, redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from verify_device import (
    Runner,
    ToolFailure,
    _OwnedProcess,
    installed_apk_matches,
    main,
    sdk_path,
    select_device,
    select_target,
    ui_ready,
    write_report,
)

PROCESS_FIXTURE = """
import json, os, signal, subprocess, sys, time
from pathlib import Path
mode, directory = sys.argv[1:]
root = Path(directory)
if mode in ('stubborn', 'leaf'):
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
elif mode != 'default':
    def stopped(*_):
        print('fixture shutdown stdout', flush=True)
        print('fixture shutdown stderr', file=sys.stderr, flush=True)
        sys.exit(0)
    signal.signal(signal.SIGTERM, stopped)
child = None
if mode == 'tree':
    child = subprocess.Popen([sys.executable, '-c', os.environ['FIXTURE_SOURCE'], 'leaf', directory])
print('fixture stdout', flush=True)
print('fixture stderr', file=sys.stderr, flush=True)
(root / ('ready-' + mode)).write_text(json.dumps({'pid': os.getpid()}))
deadline = time.monotonic() + 30
while time.monotonic() < deadline:
    time.sleep(0.02)
"""


@contextmanager
def tracked_processes():
    """所有合成进程均有 finally 兜底，只终止本测试建立的会话。"""
    processes = []
    original = subprocess.Popen

    def start(*args, **kwargs):
        process = original(*args, **kwargs)
        processes.append(process)
        return process

    try:
        with patch("verify_device.subprocess.Popen", side_effect=start):
            yield processes
    finally:
        for process in processes:
            if os.name == "posix":
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            elif process.poll() is None:
                process.kill()
            process.wait(timeout=5)
            for stream in (process.stdout, process.stderr):
                if stream is not None:
                    stream.close()


class DeviceToolTest(unittest.TestCase):
    def test_installed_identity_requires_actual_matching_apk_bytes(self):
        """设备无 sha256sum 时读取合成 APK；仅相同字节通过，传输成功但内容不同仍拒绝且不留副本。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            runner = Runner(root, root, 1)
            payload = b"synthetic-installed-apk"
            expected = hashlib.sha256(payload).hexdigest()
            for content, matches in [(payload, True), (b"different-apk", False)]:

                def pull(_label, argv, *, stdout_file, content=content):
                    self.assertEqual(
                        [
                            "adb",
                            "-s",
                            "fixture-a",
                            "exec-out",
                            "run-as",
                            "com.github.zzwtsy.chuanmen",
                            "cat",
                            "/data/app/base.apk",
                        ],
                        argv,
                    )
                    stdout_file.write_bytes(content)
                    return ""

                with (
                    self.subTest(matches=matches),
                    patch.object(runner, "run", side_effect=pull),
                ):
                    self.assertEqual(
                        matches,
                        installed_apk_matches(
                            runner,
                            "adb",
                            "fixture-a",
                            "/data/app/base.apk",
                            expected,
                            "app",
                        ),
                    )
                self.assertEqual([], list(root.iterdir()))

    def test_binary_stdout_is_preserved_without_entering_text_log(self):
        """二进制包含非法 UTF-8 时执行采集，断言原字节完整保存，诊断只保留 stderr。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            runner = Runner(root, root, 2)
            target = root / "payload.apk"
            result = runner.run(
                "binary",
                [
                    sys.executable,
                    "-c",
                    "import sys; sys.stdout.buffer.write(bytes([0,255,13,10])); print('diagnostic', file=sys.stderr)",
                ],
                stdout_file=target,
            )
            self.assertEqual(bytes([0, 255, 13, 10]), target.read_bytes())
            self.assertEqual("diagnostic\n", result)
            self.assertEqual(result, (root / "binary.log").read_text())

    def fixture_argv(self, root: Path, mode: str) -> list[str]:
        return [sys.executable, "-c", PROCESS_FIXTURE, mode, str(root)]

    def wait_ready(self, root: Path, mode: str, process: subprocess.Popen) -> dict:
        deadline = time.monotonic() + 10
        path = root / f"ready-{mode}"
        while time.monotonic() < deadline:
            if path.is_file():
                try:
                    data = json.loads(path.read_text())
                    if mode == "tree":
                        self.wait_ready(root, "leaf", process)
                    return data
                except json.JSONDecodeError:
                    pass
            self.assertIsNone(process.poll(), "fixture 在就绪握手前结束")
            time.sleep(0.01)
        self.fail("fixture 就绪握手超时")

    @contextmanager
    def ready_runner(self, root: Path, mode: str):
        runner = Runner(root, root, 0.15)
        wait = runner._wait

        def ready_then_wait(process):
            self.wait_ready(root, mode, process)
            wait(process)

        with (
            tracked_processes(),
            patch.object(runner, "_wait", side_effect=ready_then_wait),
            patch("verify_device.TERMINATE_GRACE_SECONDS", 0.2),
            patch("verify_device.KILL_WAIT_SECONDS", 0.2),
            patch.dict(os.environ, {"FIXTURE_SOURCE": PROCESS_FIXTURE}),
        ):
            yield runner

    def cli(self, root: Path, action) -> tuple[int, dict | None, str]:
        errors = io.StringIO()
        with (
            patch("verify_device.ROOT", root),
            patch("sys.argv", ["verify_device.py", "--case", "bridge"]),
            patch("verify_device.verify_device", side_effect=action),
            redirect_stdout(io.StringIO()),
            redirect_stderr(errors),
        ):
            code = main()
        path = max(
            (root / "app/build/device-tests/bridge").glob("run-*/result.json"),
            default=root / "missing.json",
        )
        return (
            code,
            json.loads(path.read_text()) if path.exists() else None,
            errors.getvalue(),
        )

    def write(self, root: Path, name: str, content: str) -> None:
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def test_local_sdk_overrides_environment_and_resolves_escaped_relative_path(
        self,
    ) -> None:
        """本地 SDK 使用含转义空格的相对路径且环境另指两个目录时定位，断言选择与 AGP 相同的本地目录。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            for name in ("local sdk", "environment", "legacy"):
                (root / name).mkdir()
            self.write(root, "local.properties", "sdk.dir=local\\ sdk\n")
            with patch.dict(
                os.environ,
                {
                    "ANDROID_HOME": str(root / "environment"),
                    "ANDROID_SDK_ROOT": str(root / "legacy"),
                },
                clear=True,
            ):
                self.assertEqual(root / "local sdk", sdk_path(root))

    def test_invalid_local_sdk_falls_back_to_valid_environment(self) -> None:
        """本地 SDK 路径不存在而 ANDROID_HOME 有效时定位，断言使用环境目录而不是拒绝可用环境。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            sdk = root / "sdk"
            sdk.mkdir()
            self.write(root, "local.properties", "sdk.dir=missing\n")
            with patch.dict(os.environ, {"ANDROID_HOME": str(sdk)}, clear=True):
                self.assertEqual(sdk, sdk_path(root))

    def test_sdk_root_fallback_and_environment_conflict(self) -> None:
        """仅旧 SDK 环境变量有效时定位成功；两个变量分别指向有效目录时定位失败并明确指出冲突。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            sdk = root / "sdk"
            sdk.mkdir()
            other = root / "other"
            other.mkdir()
            with patch.dict(os.environ, {"ANDROID_SDK_ROOT": str(sdk)}, clear=True):
                self.assertEqual(sdk, sdk_path(root))
                os.environ["ANDROID_HOME"] = str(other)
                with self.assertRaises(ToolFailure) as failure:
                    sdk_path(root)
            self.assertEqual(3, failure.exception.code)
            self.assertIn("SDK_CONFLICT", str(failure.exception))

    def test_sdk_without_valid_directory_is_blocked(self) -> None:
        """本地与环境均没有有效 SDK 目录时定位，断言返回前置缺失而不误报平台可用。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            with (
                patch.dict(
                    os.environ, {"ANDROID_HOME": str(root / "missing")}, clear=True
                ),
                self.assertRaises(ToolFailure) as failure,
            ):
                sdk_path(root)
            self.assertEqual(3, failure.exception.code)
            self.assertIn("SDK_MISSING", str(failure.exception))

    def test_no_device_is_blocked(self) -> None:
        """没有在线设备时执行选择，断言退出分类为设备阻塞，不能把空列表写成通过。"""
        with self.assertRaises(ToolFailure) as failure:
            select_device("List of devices attached\n", None)
        self.assertEqual(5, failure.exception.code)

    def test_unauthorized_and_wrong_device_are_blocked(self) -> None:
        """未授权设备或指定错误设备时选择测试目标，断言都返回设备阻塞。"""
        for output, requested in (
            ("fixture-a\tunauthorized\n", None),
            ("fixture-a\tdevice\n", "fixture-b"),
        ):
            with (
                self.subTest(requested=requested),
                self.assertRaises(ToolFailure) as failure,
            ):
                select_device(output, requested)
            self.assertEqual(5, failure.exception.code)

    def test_single_device_is_selected(self) -> None:
        """仅有一个已授权设备且 ID 匹配时选择目标，断言返回该合成设备 ID。"""
        self.assertEqual("fixture-a", select_device("fixture-a\tdevice\n", "fixture-a"))

    def test_explicit_target_can_coexist_with_other_online_devices(self):
        """两个已授权设备在线时显式选中一个，断言不误选另一台；隐式选择仍阻塞。"""
        output = (
            "List of devices attached\nemulator-5554\tdevice\nfixture-phone\tdevice\n"
        )
        self.assertEqual("fixture-phone", select_target(output, "fixture-phone"))
        with self.assertRaises(ToolFailure):
            select_target(output, None)
        with self.assertRaises(ToolFailure):
            select_target(output, "missing")

    def test_live_activity_requires_observable_awake_and_unlocked_state(self):
        """亮屏且锁屏字段均为 false 才启动 Activity；锁定、息屏或缺测不会进入安装。"""
        unlocked = "mInputRestricted=false\nmShowingLockscreen=false\nmDreamingLockscreen=false"
        self.assertTrue(ui_ready("mWakefulness=Awake", unlocked))
        for power, window in (
            ("mWakefulness=Dozing", unlocked),
            ("", unlocked),
            ("mWakefulness=Awake", ""),
            (
                "mWakefulness=Awake",
                unlocked.replace("mInputRestricted=false", "mInputRestricted=true"),
            ),
        ):
            with self.subTest(power=power, window=window):
                self.assertFalse(ui_ready(power, window))

    def test_failed_command_preserves_exit_and_log(self) -> None:
        """子命令返回 17 和错误输出时执行 runner，断言分类为任务失败并保留退出码及完整日志。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            runner = Runner(root, root, 30)
            with (
                tracked_processes(),
                self.assertRaises(ToolFailure) as failure,
            ):
                runner.run(
                    "fixture",
                    [
                        sys.executable,
                        "-c",
                        "import sys; print('fixture output'); print('fixture error', file=sys.stderr); sys.exit(17)",
                    ],
                )
            self.assertEqual(4, failure.exception.code)
            self.assertEqual("failed", runner.records[0]["status"])
            self.assertEqual(17, runner.records[0]["exit_code"])
            self.assertEqual(
                "fixture output\nfixture error\n", (root / "fixture.log").read_text()
            )

    def test_timeout_retains_both_streams_even_when_term_returns_zero(self) -> None:
        """就绪进程输出两路日志、捕获 TERM 后返回 0 时触发超时，断言仍失败且完整记录输出和清理结果。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            with (
                self.ready_runner(root, "cooperative") as runner,
                self.assertRaises(ToolFailure) as failure,
            ):
                runner.run("fixture", self.fixture_argv(root, "cooperative"))
            self.assertEqual(6, failure.exception.code)
            record = runner.records[0]
            self.assertEqual("timed_out", record["status"])
            self.assertTrue(record["timed_out"])
            self.assertEqual(0, record["exit_code"])
            self.assertIsNone(record["termination_signal"])
            self.assertTrue(record["cleanup_complete"])
            self.assertIsNone(record["cleanup_error"])
            self.assertEqual(
                "fixture stdout\nfixture shutdown stdout\nfixture stderr\nfixture shutdown stderr\n",
                (root / "fixture.log").read_text(),
            )

    def test_missing_tool_is_prerequisite_failure(self) -> None:
        """命令不在 PATH 且源码树没有该文件时执行 runner，断言前置缺失并且不调用子进程。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            runner = Runner(root, root, 30)
            with (
                patch("verify_device.shutil.which", return_value=None),
                patch("verify_device.subprocess.Popen") as start,
                self.assertRaises(ToolFailure) as failure,
            ):
                runner.run("fixture", ["fixture-tool"])
            self.assertEqual(3, failure.exception.code)
            start.assert_not_called()
            record = runner.records[0]
            self.assertEqual("blocked", record["status"])
            self.assertIsNone(record["exit_code"])
            self.assertIsNone(record["termination_signal"])
            self.assertEqual("", (root / "fixture.log").read_text())

    def test_spawn_failure_is_recorded_without_exit_code(self) -> None:
        """已找到工具但启动权限被拒绝时执行，断言失败步骤保留，退出码和实际终止信号均为空。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            runner = Runner(root, root, 30)
            with (
                patch(
                    "verify_device.subprocess.Popen",
                    side_effect=PermissionError("fixture denied"),
                ),
                self.assertRaises(ToolFailure) as failure,
            ):
                runner.run("fixture", [sys.executable])
            self.assertEqual(4, failure.exception.code)
            self.assertEqual("failed", runner.records[0]["status"])
            self.assertIsNone(runner.records[0]["exit_code"])
            self.assertIsNone(runner.records[0]["termination_signal"])
            self.assertIsNone(runner.records[0]["cleanup_complete"])

    @unittest.skipUnless(os.name == "posix", "进程组强制清理需要 POSIX")
    def test_ignored_term_is_killed_and_waited(self) -> None:
        """就绪进程忽略 TERM 时触发超时，断言预算内升级到 KILL、等待实际退出并记录真实信号。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            with self.ready_runner(root, "stubborn") as runner:
                with self.assertRaises(ToolFailure):
                    runner.run("fixture", self.fixture_argv(root, "stubborn"))
                record = runner.records[0]
                self.assertEqual(-signal.SIGKILL, record["exit_code"])
                self.assertEqual(signal.SIGKILL, record["termination_signal"])
                self.assertEqual(
                    [signal.SIGTERM, signal.SIGKILL], record["cleanup_signals"]
                )
                self.assertTrue(record["cleanup_complete"])
                self.assertLess(record["elapsed_seconds"], 3)

    @unittest.skipUnless(os.name == "posix", "实际终止信号需要 POSIX")
    def test_default_term_exit_records_actual_signal(self) -> None:
        """就绪进程使用默认 TERM 行为时触发超时，断言记录实际 TERM 终止且没有误记为 KILL。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            with self.ready_runner(root, "default") as runner:
                with self.assertRaises(ToolFailure):
                    runner.run("fixture", self.fixture_argv(root, "default"))
                self.assertEqual(-signal.SIGTERM, runner.records[0]["exit_code"])
                self.assertEqual(
                    signal.SIGTERM, runner.records[0]["termination_signal"]
                )
                self.assertEqual([signal.SIGTERM], runner.records[0]["cleanup_signals"])

    @unittest.skipUnless(
        sys.platform.startswith("linux"), "衍生进程资源确认以 Linux 验收"
    )
    def test_exited_parent_does_not_leave_descendant_or_kill_unrelated_process(
        self,
    ) -> None:
        """父进程捕获 TERM 退出、叶子忽略 TERM 且另有独立任务时超时，断言叶子收束、独立任务继续运行。"""
        with TemporaryDirectory() as directory, tracked_processes():
            root = Path(directory)
            unrelated = subprocess.Popen(
                self.fixture_argv(root, "unrelated"),
                start_new_session=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            self.wait_ready(root, "unrelated", unrelated)
            with self.ready_runner(root, "tree") as runner:
                with self.assertRaises(ToolFailure):
                    runner.run("fixture", self.fixture_argv(root, "tree"))
                leaf = json.loads((root / "ready-leaf").read_text())["pid"]
                stat = Path(f"/proc/{leaf}/stat")
                if stat.exists():
                    self.assertIn(
                        stat.read_text().rsplit(")", 1)[1].split()[0], ("Z", "X")
                    )
                self.assertEqual(0, runner.records[0]["exit_code"])
                self.assertTrue(runner.records[0]["cleanup_complete"])
                self.assertEqual("process_group", runner.records[0]["cleanup_scope"])
                self.assertIsNone(unrelated.poll())

    @unittest.skipUnless(os.name == "posix", "幂等进程组清理需要 POSIX")
    def test_cleanup_is_idempotent(self) -> None:
        """同一进程完成清理后再次请求清理，断言直接子进程已退出且不会再向进程组发送信号。"""
        with TemporaryDirectory() as directory, tracked_processes():
            root = Path(directory)
            process = subprocess.Popen(
                self.fixture_argv(root, "cooperative"),
                start_new_session=True,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
            self.wait_ready(root, "cooperative", process)
            owned = _OwnedProcess(process)
            owned.cleanup()
            self.assertEqual(0, process.returncode)
            with patch("verify_device.os.killpg") as kill:
                owned.cleanup()
            kill.assert_not_called()
            self.assertTrue(owned.complete)

    def test_cleanup_budget_failure_is_reported_and_bounded(self) -> None:
        """无法确认资源已结束时触发超时，断言清理在预算后返回、明确未完成且顶层仍为失败。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)

            def action(runner, _offline):
                wait = runner._wait
                runner.timeout = 0.15

                def ready_then_wait(process):
                    self.wait_ready(root, "cooperative", process)
                    wait(process)

                with (
                    patch.object(runner, "_wait", side_effect=ready_then_wait),
                    patch.object(_OwnedProcess, "alive", return_value=True),
                    patch("verify_device.TERMINATE_GRACE_SECONDS", 0.1),
                    patch("verify_device.KILL_WAIT_SECONDS", 0.1),
                ):
                    runner.run("fixture", self.fixture_argv(root, "cooperative"))

            with tracked_processes():
                code, report, _ = self.cli(root, action)
            self.assertEqual(6, code)
            self.assertEqual(code, report["exit_code"])
            record = report["steps"][0]
            self.assertFalse(record["cleanup_complete"])
            self.assertIn("CLEANUP_INCOMPLETE", record["cleanup_error"])
            self.assertLess(record["elapsed_seconds"], 3)

    @unittest.skipUnless(os.name == "posix", "收束期间的中断需要 POSIX")
    def test_interrupt_during_timeout_cleanup_retains_both_events(self) -> None:
        """步骤先超时、收束期间再收到 TERM 时执行，断言最终为中断且超时事实、清理结果仍保留。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            with self.ready_runner(root, "cooperative") as runner:
                cleanup = _OwnedProcess.cleanup

                def interrupt_then_cleanup(owned):
                    runner.interruption.receive(signal.SIGTERM, None)
                    cleanup(owned)

                with (
                    patch.object(_OwnedProcess, "cleanup", interrupt_then_cleanup),
                    self.assertRaises(ToolFailure) as failure,
                ):
                    runner.run("fixture", self.fixture_argv(root, "cooperative"))
                self.assertEqual(143, failure.exception.code)
                self.assertEqual("interrupted", runner.records[0]["status"])
                self.assertTrue(runner.records[0]["timed_out"])
                self.assertTrue(runner.records[0]["interrupted"])
                self.assertTrue(runner.records[0]["cleanup_complete"])

    def test_cli_report_matches_success_and_failure(self) -> None:
        """成功、非零退出、缺工具和启动失败经 CLI 执行，断言顶层分类、退出码和步骤记录一致。"""
        cases = (
            ("passed", 0, "print('fixture')"),
            ("failed", 4, "raise SystemExit(17)"),
            ("blocked", 3, None),
            ("spawn", 4, "fixture"),
        )
        for status, expected_code, source in cases:
            with self.subTest(status=status), TemporaryDirectory() as directory:
                root = Path(directory)

                def action(runner, _offline, *, source=source, status=status):
                    if source is None:
                        runner.run("fixture", ["fixture-missing-tool"])
                    elif status == "spawn":
                        with patch(
                            "verify_device.subprocess.Popen",
                            side_effect=PermissionError("fixture denied"),
                        ):
                            runner.run("fixture", [sys.executable])
                    else:
                        runner.run("fixture", [sys.executable, "-c", source])

                with tracked_processes():
                    code, report, _ = self.cli(root, action)
                self.assertEqual(expected_code, code)
                self.assertEqual(code, report["exit_code"])
                self.assertEqual(
                    "failed" if status == "spawn" else status, report["status"]
                )
                self.assertEqual(report["status"], report["steps"][0]["status"])
                self.assertEqual(1, report["schema_version"])
                self.assertEqual("verify-device", report["command"])
                if code:
                    self.assertEqual("fixture", report["failure"]["step"])
                else:
                    self.assertIsNone(report["failure"])

    def test_failed_retry_cannot_claim_previous_success(self):
        """首次成功后重试缺少前置，断言新轮次失败、旧报告保留且两轮结果不混用。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.assertEqual(0, self.cli(root, lambda *_: None)[0])
            code, report, _ = self.cli(root, ToolFailure(3, "fixture missing peer"))
            self.assertEqual(3, code)
            self.assertEqual("blocked", report["status"])
            reports = [
                json.loads(path.read_text())
                for path in (root / "app/build/device-tests/bridge").glob(
                    "run-*/result.json"
                )
            ]
            self.assertEqual(
                ["blocked", "passed"], sorted(item["status"] for item in reports)
            )

    def test_cli_preflight_failure_still_creates_top_level_report(self) -> None:
        """有效命令在执行任何步骤前缺少前置或读文件失败，断言空步骤报告仍有非零退出码和原因。"""
        for error, expected_code in (
            (ToolFailure(3, "fixture prerequisite"), 3),
            (OSError("fixture read denied"), 4),
        ):
            with self.subTest(code=expected_code), TemporaryDirectory() as directory:
                code, report, _ = self.cli(Path(directory), error)
                self.assertEqual(expected_code, code)
                self.assertEqual(code, report["exit_code"])
                self.assertEqual([], report["steps"])
                self.assertIsNone(report["failure"]["step"])
                self.assertIn("fixture", report["failure"]["message"])

    @unittest.skipUnless(os.name == "posix", "CLI 信号回归需要 POSIX")
    def test_real_cli_sigint_and_sigterm_are_reported_even_with_zero_child_exit(
        self,
    ) -> None:
        """真实 CLI 收到 INT 或 TERM、子进程捕获清理信号返回 0，断言报告为中断且 CLI 返回 130 或 143。"""
        bootstrap = f"""
import sys
from pathlib import Path
sys.path.insert(0, {str(Path(__file__).resolve().parent)!r})
import verify_device as tools
root = Path(sys.argv[1])
tools.ROOT = root
def build(runner, offline):
    runner.run('fixture', [sys.executable, '-c', {PROCESS_FIXTURE!r}, 'cooperative', str(root)])
tools.verify_device = build
sys.argv = ['verify_device.py', '--case', 'bridge']
raise SystemExit(tools.main())
"""
        for signum in (signal.SIGINT, signal.SIGTERM):
            with (
                self.subTest(signum=signum),
                TemporaryDirectory() as directory,
                tracked_processes(),
            ):
                root = Path(directory)
                cli = subprocess.Popen(
                    [sys.executable, "-c", bootstrap, str(root)],
                    start_new_session=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.PIPE,
                )
                ready = None
                try:
                    ready = self.wait_ready(root, "cooperative", cli)
                    os.kill(cli.pid, signum)
                    # 连续中断不能提前结束清理或破坏报告。
                    os.kill(cli.pid, signum)
                    cli.communicate(timeout=12)
                    self.assertEqual(128 + signum, cli.returncode)
                    report = json.loads(
                        next(
                            (root / "app/build/device-tests/bridge").glob(
                                "run-*/result.json"
                            )
                        ).read_text()
                    )
                    self.assertEqual(cli.returncode, report["exit_code"])
                    self.assertEqual("interrupted", report["status"])
                    record = report["steps"][0]
                    self.assertTrue(record["interrupted"])
                    self.assertEqual(signum, record["interruption_signal"])
                    self.assertEqual(0, record["exit_code"])
                    self.assertTrue(record["cleanup_complete"])
                    self.assertIsNone(record["termination_signal"])
                finally:
                    if cli.poll() is None:
                        cli.terminate()
                        try:
                            cli.communicate(timeout=12)
                        except subprocess.TimeoutExpired:
                            cli.kill()
                    # CLI 的子命令建立了另一进程组，外层进程清理不能代替它的兜底。
                    if ready is None and (root / "ready-cooperative").is_file():
                        ready = json.loads((root / "ready-cooperative").read_text())
                    if ready is not None:
                        try:
                            os.killpg(ready["pid"], signal.SIGKILL)
                        except ProcessLookupError:
                            pass

    def test_cli_restores_previous_signal_handlers(self) -> None:
        """宿主已安装 INT / TERM 处理器时调用 CLI，断言成功和失败退出均恢复宿主处理器。"""
        previous = {
            value: signal.getsignal(value) for value in (signal.SIGINT, signal.SIGTERM)
        }
        for action in (lambda *_: None, ToolFailure(3, "fixture prerequisite")):
            with TemporaryDirectory() as directory:
                self.cli(Path(directory), action)
            for value, handler in previous.items():
                self.assertEqual(handler, signal.getsignal(value))

    def test_log_write_failure_cannot_become_success(self) -> None:
        """子命令成功但步骤日志写入失败时执行 CLI，断言报告记录日志错误且顶层不能成功。"""
        original = Path.write_text

        def write(path, *args, **kwargs):
            if path.name == "fixture.log":
                raise OSError("fixture log denied")
            return original(path, *args, **kwargs)

        with TemporaryDirectory() as directory, tracked_processes():

            def action(runner, _offline):
                runner.run("fixture", [sys.executable, "-c", "print('fixture')"])

            with patch("verify_device.Path.write_text", write):
                code, report, _ = self.cli(Path(directory), action)
            self.assertEqual(4, code)
            self.assertEqual("failed", report["status"])
            self.assertEqual(0, report["steps"][0]["exit_code"])
            self.assertIn("fixture log denied", report["steps"][0]["log_error"])

    def test_report_write_error_is_independent_and_nonzero(self) -> None:
        """步骤成功但报告写入被拒绝时执行 CLI，断言返回任务失败并经 stderr 提供独立错误。"""
        with (
            TemporaryDirectory() as directory,
            patch(
                "verify_device.write_report",
                side_effect=OSError("fixture report denied"),
            ),
        ):
            code, report, errors = self.cli(Path(directory), lambda *_: None)
        self.assertEqual(4, code)
        self.assertIsNone(report)
        self.assertIn("REPORT_WRITE_FAILED", errors)
        self.assertIn("fixture report denied", errors)

    def test_atomic_report_failure_preserves_previous_report(self) -> None:
        """原报告存在但原子替换失败时写报告，断言旧文件不被截断且临时文件已移除。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / "result.json"
            path.write_text("fixture previous report")
            with (
                patch(
                    "verify_device.Path.replace",
                    side_effect=OSError("fixture replace denied"),
                ),
                self.assertRaises(OSError),
            ):
                write_report(path, {"status": "passed"})
            self.assertEqual("fixture previous report", path.read_text())
            self.assertEqual([path], list(root.iterdir()))


if __name__ == "__main__":
    unittest.main()
