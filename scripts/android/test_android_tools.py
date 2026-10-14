"""Android 入口与边界检查回归；不启动 Gradle、ADB 或真实设备。"""

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

from android_tools import (
    Runner,
    ToolFailure,
    _OwnedProcess,
    doctor,
    main,
    native_candidate,
    sdk_path,
    select_device,
    write_report,
)
from check_boundaries import violations

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
        with patch("android_tools.subprocess.Popen", side_effect=start):
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


class AndroidToolsTest(unittest.TestCase):
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
            patch("android_tools.TERMINATE_GRACE_SECONDS", 0.2),
            patch("android_tools.KILL_WAIT_SECONDS", 0.2),
            patch.dict(os.environ, {"FIXTURE_SOURCE": PROCESS_FIXTURE}),
        ):
            yield runner

    def cli(self, root: Path, action) -> tuple[int, dict | None, str]:
        errors = io.StringIO()
        with (
            patch("android_tools.ROOT", root),
            patch("sys.argv", ["android_tools.py", "build-demo"]),
            patch("android_tools.build_demo", side_effect=action),
            redirect_stdout(io.StringIO()),
            redirect_stderr(errors),
        ):
            code = main()
        path = root / "build/android/build-demo/result.json"
        return (
            code,
            json.loads(path.read_text()) if path.exists() else None,
            errors.getvalue(),
        )

    def write(self, root: Path, name: str, content: str) -> None:
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def baseline(self, root: Path) -> None:
        self.write(
            root,
            "contracts/build.gradle.kts",
            "dependencies {\n api(libs.kotlinx.coroutines.core)\n testImplementation(libs.junit)\n}\n",
        )
        self.write(
            root,
            "app/build.gradle.kts",
            'implementation(project(":contracts"))\ndependsOn(verifyLiveEngine)\n// LIVE_ENGINE_MISSING\n',
        )

    def candidate(self, root: Path) -> dict:
        commit = "a" * 40
        lock = b"fixture locked dependencies"
        lock_hash = hashlib.sha256(lock).hexdigest()
        patch = {
            "core_commit": commit,
            "production_approved": False,
            "lockfile_sha256": lock_hash,
        }
        reference = {
            "candidate_commit": commit,
            "cargo_lock_sha256": lock_hash,
            "ndk_revision": "30.0.16248370",
            "abis": ["arm64-v8a", "x86_64"],
            "tool_versions": {
                "rustc": "release: 1.99.0\ncommit-hash: " + "b" * 40 + "\n"
            },
        }
        contents = {
            "core_cargo_lock": lock,
            "patch_diff": b"fixture six-file diff",
            "patch_record": json.dumps(patch).encode(),
            "reference_build": json.dumps(reference).encode(),
        }
        inputs = {}
        for name, content in contents.items():
            path = f"fixtures/{name}"
            self.write(root, path, content.decode())
            inputs[name] = {"path": path, "sha256": hashlib.sha256(content).hexdigest()}
        data = {
            "schema_version": 1,
            "status": "candidate",
            "production_approved": False,
            "upstream": {"commit": commit},
            "toolchain": {
                "ndk_revision": "30.0.16248370",
                "abis": ["arm64-v8a", "x86_64"],
                "rust_version": "1.99.0",
                "rust_commit": "b" * 40,
            },
            "inputs": inputs,
        }
        self.write(root, "gradle/native-candidate.json", json.dumps(data))
        return data

    def doctor_fixture(self, root: Path) -> Runner:
        self.candidate(root)
        self.write(
            root,
            "app/build.gradle.kts",
            'compileSdk { version = release(37) }\nbuildToolsVersion = "36.0.0"\n',
        )
        self.write(root, "sdk/platforms/android-37.0/android.jar", "fixture")
        self.write(root, "sdk/build-tools/36.0.0/source.properties", "fixture")
        self.write(root, "local.properties", "sdk.dir=sdk\n")
        return Runner(root, root, 30)

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

    def test_doctor_uses_java_home_when_java_is_absent_from_path(self) -> None:
        """PATH 没有 java 而 JAVA_HOME 提供可执行程序时诊断，断言实际运行该程序并保留版本日志。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.doctor_fixture(root)
            jdk = root / "jdk"
            self.write(jdk, "bin/java", '#!/bin/sh\nprintf "fixture JAVA_HOME\\n"\n')
            (jdk / "bin/java").chmod(0o755)
            run_command = runner.run

            def run(label, argv, env=None):
                return (
                    run_command(label, argv, env)
                    if label == "java"
                    else "fixture version"
                )

            with (
                patch.dict(os.environ, {"JAVA_HOME": str(jdk), "PATH": ""}, clear=True),
                patch.object(runner, "run", side_effect=run),
            ):
                doctor(runner, True, False)
            self.assertEqual("fixture JAVA_HOME\n", (root / "java.log").read_text())
            self.assertEqual(0, runner.records[0]["exit_code"])

    def test_doctor_does_not_fall_back_from_invalid_java_home(self) -> None:
        """JAVA_HOME 指向缺少 java 的目录时诊断，断言前置失败，与 Wrapper 一样不改用 PATH 中的其他 JDK。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.doctor_fixture(root)
            with (
                patch.dict(os.environ, {"JAVA_HOME": str(root / "missing")}),
                self.assertRaises(ToolFailure) as failure,
            ):
                doctor(runner, True, False)
            self.assertEqual(3, failure.exception.code)
            self.assertIn("TOOL_MISSING", str(failure.exception))
            self.assertIn(str(root / "missing/bin/java"), str(failure.exception))
            self.assertEqual("blocked", runner.records[0]["status"])
            self.assertIsNone(runner.records[0]["exit_code"])

    def test_optional_rust_failure_does_not_block_demo_doctor(self) -> None:
        """Rust shim 存在但没有可用工具链时诊断 demo，断言记录诊断失败原因并继续说明 live 阻断。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.doctor_fixture(root)
            output = io.StringIO()

            def run(label, argv, env=None):
                if label in ("rust", "rust-targets"):
                    return run_command(
                        label, [sys.executable, "-c", "raise SystemExit(17)"]
                    )
                return "fixture version"

            run_command = runner.run
            with (
                patch.object(runner, "run", side_effect=run),
                patch("android_tools.shutil.which", return_value="fixture shim"),
                redirect_stdout(output),
            ):
                doctor(runner, True, False)
            self.assertIn("rustc 诊断失败（demo 不要求）", output.getvalue())
            self.assertIn("rustup 诊断失败（demo 不要求）", output.getvalue())
            self.assertIn("live 阻断原因", output.getvalue())
            self.assertEqual(2, len(runner.records))
            for record in runner.records:
                self.assertEqual("failed", record["status"])
                self.assertEqual(17, record["exit_code"])
                self.assertTrue(record["failure_handled"])

    def test_doctor_preserves_required_failure_and_optional_timeout(self) -> None:
        """必需的 JDK 命令失败或可选 Rust 命令超时时诊断，断言原失败分类保留，不能被 demo 宽容策略吞掉。"""
        for label, code in (("java", 4), ("rust", 6)):
            with self.subTest(label=label), TemporaryDirectory() as directory:
                root = Path(directory)
                runner = self.doctor_fixture(root)

                def run(
                    current, argv, env=None, *, failure_label=label, failure_code=code
                ):
                    if current == failure_label:
                        raise ToolFailure(failure_code, "fixture failure")
                    return "fixture version"

                with (
                    patch.object(runner, "run", side_effect=run),
                    patch("android_tools.shutil.which", return_value="fixture shim"),
                    self.assertRaises(ToolFailure) as failure,
                ):
                    doctor(runner, True, False)
                self.assertEqual(code, failure.exception.code)

    def test_candidate_inputs_match_frozen_sources(self) -> None:
        """候选版本及四个输入与冻结来源相符时核对，断言返回候选且生产批准仍为 false。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            expected = self.candidate(root)
            self.assertEqual(expected, native_candidate(root))
            self.assertIs(native_candidate(root)["production_approved"], False)

    def test_changed_candidate_source_is_rejected(self) -> None:
        """登记摘要后修改补丁内容时核对，断言任务失败并明确指出来源摘要不匹配。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            data = self.candidate(root)
            self.write(root, data["inputs"]["patch_diff"]["path"], "changed diff")
            with self.assertRaises(ToolFailure) as failure:
                native_candidate(root)
            self.assertEqual(4, failure.exception.code)
            self.assertIn("来源摘要不匹配", str(failure.exception))

    def test_candidate_cannot_approve_production_or_change_abi(self) -> None:
        """把候选批准改为 true 或换成未验证 ABI 后核对，断言均失败，不能靠清单改写扩大能力。"""
        for field in ("approval", "abi"):
            with self.subTest(field=field), TemporaryDirectory() as directory:
                root = Path(directory)
                data = self.candidate(root)
                if field == "approval":
                    data["production_approved"] = True
                else:
                    data["toolchain"]["abis"] = ["armeabi-v7a"]
                self.write(root, "gradle/native-candidate.json", json.dumps(data))
                with self.assertRaises(ToolFailure) as failure:
                    native_candidate(root)
                self.assertEqual(4, failure.exception.code)

    def test_doctor_requires_declared_build_tools(self) -> None:
        """SDK 只有其他构建工具版本时运行诊断，断言前置缺失并指出工程固定版本，不能误报环境可用。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.candidate(root)
            self.write(
                root,
                "app/build.gradle.kts",
                'compileSdk { version = release(37) }\nbuildToolsVersion = "36.0.0"\n',
            )
            sdk = root / "sdk"
            self.write(sdk, "platforms/android-37.0/android.jar", "fixture")
            self.write(sdk, "build-tools/35.0.0/source.properties", "fixture")
            runner = Runner(root, root, 30)
            with (
                patch("android_tools.sdk_path", return_value=sdk),
                patch.object(runner, "run", return_value="fixture version"),
                self.assertRaises(ToolFailure) as failure,
            ):
                doctor(runner, True, False)
            self.assertEqual(3, failure.exception.code)
            self.assertIn("build-tools 36.0.0", str(failure.exception))

    def test_no_device_is_blocked(self) -> None:
        """没有在线设备时执行选择，断言退出分类为设备阻塞，不能把空列表写成通过。"""
        with self.assertRaises(ToolFailure) as failure:
            select_device("List of devices attached\n", None)
        self.assertEqual(5, failure.exception.code)

    def test_multiple_devices_are_blocked_even_with_serial(self) -> None:
        """多个设备在线时指定其中一个，断言仍阻塞，避免 Gradle 对未选设备安装测试 APK。"""
        with self.assertRaises(ToolFailure) as failure:
            select_device("fixture-a\tdevice\nfixture-b\tdevice\n", "fixture-a")
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

    def test_pure_contract_and_local_slice_are_allowed(self) -> None:
        """契约仅依赖协程且配置切片只导入公开契约时检查，断言没有边界违规。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.baseline(root)
            self.write(
                root,
                "app/src/main/java/feature/configuration/Edit.kt",
                "import com.github.zzwtsy.chuanmen.contracts.ProfileRepository\n",
            )
            self.assertEqual([], violations(root))

    def test_android_in_contract_is_rejected(self) -> None:
        """纯 Kotlin 模块引入 Android 类型时检查，断言输出对应源码的边界违规。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.baseline(root)
            self.write(
                root,
                "contracts/src/main/kotlin/Bad.kt",
                "import android.content.Context\n",
            )
            self.assertTrue(any("Bad.kt" in error for error in violations(root)))

    def test_cross_slice_and_ui_engine_are_rejected(self) -> None:
        """日志页直接导入配置私有编辑类型及 CoreEngine 时检查，断言同时报告切片和页面越界。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.baseline(root)
            self.write(
                root,
                "app/src/main/java/feature/logs/LogsScreen.kt",
                "import com.github.zzwtsy.chuanmen.feature.configuration.edit.Draft\nimport com.github.zzwtsy.chuanmen.contracts.CoreEngine\n",
            )
            errors = violations(root)
            self.assertTrue(any("切片不能" in error for error in errors))
            self.assertTrue(any("页面不能" in error for error in errors))

    def test_live_fake_and_reverse_module_dependency_are_rejected(self) -> None:
        """live 入口引用 fake、contracts 反向依赖 app 时检查，断言两类违规均被发现。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.baseline(root)
            self.write(
                root, "contracts/build.gradle.kts", 'implementation(project(":app"))'
            )
            self.write(
                root,
                "app/src/live/java/Assembly.kt",
                "com.github.zzwtsy.chuanmen.application.demo.FakeEngine()",
            )
            errors = violations(root)
            self.assertTrue(any("不能依赖业务模块" in error for error in errors))
            self.assertTrue(any("不能引用 demo" in error for error in errors))

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
                patch("android_tools.shutil.which", return_value=None),
                patch("android_tools.subprocess.Popen") as start,
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
                    "android_tools.subprocess.Popen",
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
            with patch("android_tools.os.killpg") as kill:
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
                    patch("android_tools.TERMINATE_GRACE_SECONDS", 0.1),
                    patch("android_tools.KILL_WAIT_SECONDS", 0.1),
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

    def test_optional_rust_cleanup_error_remains_blocking(self) -> None:
        """可选 Rust 普通退出失败同时出现清理错误时诊断，断言不会把错误标成已处理或返回成功。"""
        with TemporaryDirectory() as directory, tracked_processes():
            root = Path(directory)
            runner = self.doctor_fixture(root)
            run_command = runner.run

            def run(label, argv, env=None):
                if label == "rust":
                    return run_command(
                        label, [sys.executable, "-c", "raise SystemExit(17)"]
                    )
                return "fixture version"

            def cleanup(owned):
                owned.complete = False
                owned.error = "fixture cleanup denied"

            with (
                patch.object(runner, "run", side_effect=run),
                patch("android_tools.shutil.which", return_value=sys.executable),
                patch.object(_OwnedProcess, "cleanup", cleanup),
                self.assertRaises(ToolFailure) as failure,
            ):
                doctor(runner, True, False)
            self.assertEqual(4, failure.exception.code)
            self.assertFalse(runner.records[0]["failure_handled"])

    def test_optional_rust_handled_failure_allows_passed_top_level_report(self) -> None:
        """可选 Rust 命令普通非零退出、其他前置齐备时运行 CLI，断言步骤失败已处理且顶层成功。"""
        with TemporaryDirectory() as directory, tracked_processes():
            root = Path(directory)
            self.doctor_fixture(root)

            def action(runner, _offline):
                original = runner.run

                def run(label, argv, env=None):
                    return (
                        original(label, [sys.executable, "-c", "raise SystemExit(17)"])
                        if label in ("rust", "rust-targets")
                        else "fixture version"
                    )

                with (
                    patch.object(runner, "run", side_effect=run),
                    patch("android_tools.shutil.which", return_value=sys.executable),
                ):
                    doctor(runner, True, False)

            code, report, _ = self.cli(root, action)
            self.assertEqual(0, code)
            self.assertEqual("passed", report["status"])
            self.assertIsNone(report["failure"])
            self.assertEqual(2, len(report["steps"]))
            self.assertTrue(
                all(record["failure_handled"] for record in report["steps"])
            )

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
                            "android_tools.subprocess.Popen",
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
                self.assertEqual("build-demo", report["command"])
                if code:
                    self.assertEqual("fixture", report["failure"]["step"])
                else:
                    self.assertIsNone(report["failure"])

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
import android_tools as tools
root = Path(sys.argv[1])
tools.ROOT = root
def build(runner, offline):
    runner.run('fixture', [sys.executable, '-c', {PROCESS_FIXTURE!r}, 'cooperative', str(root)])
tools.build_demo = build
sys.argv = ['android_tools.py', 'build-demo']
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
                        (root / "build/android/build-demo/result.json").read_text()
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

            with patch("android_tools.Path.write_text", write):
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
                "android_tools.write_report",
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
                    "android_tools.Path.replace",
                    side_effect=OSError("fixture replace denied"),
                ),
                self.assertRaises(OSError),
            ):
                write_report(path, {"status": "passed"})
            self.assertEqual("fixture previous report", path.read_text())
            self.assertEqual([path], list(root.iterdir()))


if __name__ == "__main__":
    unittest.main()
