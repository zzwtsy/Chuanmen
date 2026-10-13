"""Android 入口与边界检查回归；不启动 Gradle、ADB 或真实设备。"""

import hashlib
import io
import json
import os
import subprocess
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

from android_tools import (
    Runner,
    ToolFailure,
    doctor,
    native_candidate,
    sdk_path,
    select_device,
)
from check_boundaries import violations


class AndroidToolsTest(unittest.TestCase):
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
            self.assertEqual([], runner.records)

    def test_optional_rust_failure_does_not_block_demo_doctor(self) -> None:
        """Rust shim 存在但没有可用工具链时诊断 demo，断言记录诊断失败原因并继续说明 live 阻断。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            runner = self.doctor_fixture(root)
            output = io.StringIO()

            def run(label, argv, env=None):
                if label in ("rust", "rust-targets"):
                    raise ToolFailure(4, "TASK_FAILED: fixture missing toolchain")
                return "fixture version"

            with (
                patch.object(runner, "run", side_effect=run),
                patch("android_tools.shutil.which", return_value="fixture shim"),
                redirect_stdout(output),
            ):
                doctor(runner, True, False)
            self.assertIn("rustc 诊断失败（demo 不要求）", output.getvalue())
            self.assertIn("rustup 诊断失败（demo 不要求）", output.getvalue())
            self.assertIn("live 阻断原因", output.getvalue())

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
            result = subprocess.CompletedProcess(
                ["java"], 17, "fixture output", "fixture error"
            )
            with (
                patch("android_tools.shutil.which", return_value="fixture-java"),
                patch("android_tools.subprocess.run", return_value=result),
                self.assertRaises(ToolFailure) as failure,
            ):
                runner.run("fixture", ["java"])
            self.assertEqual(4, failure.exception.code)
            self.assertEqual(17, runner.records[0]["exit_code"])
            self.assertEqual(
                "fixture outputfixture error", (root / "fixture.log").read_text()
            )

    def test_timeout_is_failure_with_partial_log(self) -> None:
        """子命令产生部分输出后超时时执行 runner，断言分类为超时且部分输出保存在日志中。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            runner = Runner(root, root, 30)
            with (
                patch("android_tools.shutil.which", return_value="fixture-java"),
                patch(
                    "android_tools.subprocess.run",
                    side_effect=subprocess.TimeoutExpired(
                        ["java"], 30, output=b"fixture partial"
                    ),
                ),
                self.assertRaises(ToolFailure) as failure,
            ):
                runner.run("fixture", ["java"])
            self.assertEqual(6, failure.exception.code)
            self.assertEqual("fixture partial", (root / "fixture.log").read_text())

    def test_missing_tool_is_prerequisite_failure(self) -> None:
        """命令不在 PATH 且源码树没有该文件时执行 runner，断言前置缺失并且不调用子进程。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            with (
                patch("android_tools.shutil.which", return_value=None),
                patch("android_tools.subprocess.run") as run,
                self.assertRaises(ToolFailure) as failure,
            ):
                Runner(root, root, 30).run("fixture", ["fixture-tool"])
            self.assertEqual(3, failure.exception.code)
            run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
