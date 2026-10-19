"""用临时 Gradle 工程验证依赖门禁，不编译 Android、下载制品或操作设备。"""

from __future__ import annotations

import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/android/check_module_boundaries.init.gradle.kts"
BUILDS = {
    "app": """
plugins { id 'java-library' }
configurations { liveImplementation; androidTestLiveImplementation; demoImplementation }
dependencies {
    // 声明顺序和 Groovy 写法不应影响实际依赖检查。
    androidTestLiveImplementation project(':core-bridge')
    liveImplementation project(':vpn')
    implementation project(':contracts')
}
""",
    "contracts": "plugins { id 'java-library' }\n",
    "core-bridge": """
plugins { id 'java-library' }
dependencies { api project(':contracts') }
""",
    "vpn": """
plugins { id 'java-library' }
dependencies {
    implementation project(':core-bridge')
    api project(':contracts')
}
""",
}


class ModuleBoundariesTest(unittest.TestCase):
    def fixture(self, root: Path) -> None:
        (root / "settings.gradle").write_text(
            "rootProject.name = 'boundary-fixture'\n"
            "include ':app', ':contracts', ':core-bridge', ':vpn'\n"
        )
        (root / "gradle.properties").write_text("org.gradle.configuration-cache=true\n")
        for module, content in BUILDS.items():
            directory = root / module
            directory.mkdir()
            (directory / "build.gradle").write_text(content)

    def android_plugin(self, root: Path, plugin_id: str) -> None:
        """合成插件只提供 Android 插件身份，不伪造 AGP 测试组件或解析 Android 制品。"""
        source = root / "buildSrc/src/main/java/SyntheticAndroidPlugin.java"
        source.parent.mkdir(parents=True)
        source.write_text(
            "import org.gradle.api.Plugin;\nimport org.gradle.api.Project;\n"
            "public class SyntheticAndroidPlugin implements Plugin<Project> {\n"
            "  public void apply(Project project) {}\n}\n"
        )
        (root / "buildSrc/build.gradle").write_text(
            "plugins { id 'java-gradle-plugin' }\n"
            "gradlePlugin { plugins { syntheticAndroid {\n"
            f"  id = '{plugin_id}'\n"
            "  implementationClass = 'SyntheticAndroidPlugin'\n"
            "} } }\n"
        )

    def run_gradle(self, root: Path) -> subprocess.CompletedProcess:
        return subprocess.run(
            [
                str(ROOT / "gradlew"),
                "--offline",
                "--console=plain",
                "--project-dir",
                str(root),
                "--init-script",
                str(SCRIPT),
                "checkModuleBoundaries",
            ],
            cwd=ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=180,
            check=False,
        )

    def assert_blocked(self, root: Path, *messages: str) -> None:
        result = self.run_gradle(root)
        self.assertNotEqual(0, result.returncode, result.stdout)
        for message in ("MODULE_BOUNDARY_FAILED", *messages):
            self.assertIn(message, result.stdout)

    def test_valid_graph_is_order_independent_and_reuses_configuration_cache(self):
        """合法设备依赖、倒序与延迟声明且存在不可解析制品时运行两次，断言通过并复用配置缓存。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            # 不存在的纯 JVM 制品无需解析，避免把门禁变成依赖下载任务。
            with (root / "contracts/build.gradle").open("a") as stream:
                stream.write("dependencies { api 'synthetic.jvm:unresolved:1' }\n")
            (root / "core-bridge/build.gradle").write_text(
                "plugins { id 'java-library' }\n"
                "configurations.api.withDependencies { dependencies ->\n"
                "  dependencies.add(project.dependencies.project(path: ':contracts'))\n"
                "}\n"
            )
            first = self.run_gradle(root)
            self.assertEqual(0, first.returncode, first.stdout)
            self.assertIn("MODULE_BOUNDARY_OK", first.stdout)
            second = self.run_gradle(root)
            self.assertEqual(0, second.returncode, second.stdout)
            self.assertIn("Reusing configuration cache.", second.stdout)

    def test_production_bridge_demo_vpn_and_wrong_test_configuration_are_rejected(self):
        """app 在生产、demo 或错误测试配置中添加越界依赖，断言实际配置和目标被报告。"""
        for configuration, target in (
            ("implementation", ":core-bridge"),
            ("demoImplementation", ":vpn"),
            ("testImplementation", ":core-bridge"),
        ):
            with (
                self.subTest(configuration=configuration),
                TemporaryDirectory() as directory,
            ):
                root = Path(directory)
                self.fixture(root)
                with (root / "app/build.gradle").open("a") as stream:
                    stream.write(
                        f"dependencies {{ {configuration} project('{target}') }}\n"
                    )
                self.assert_blocked(root, f":app → {configuration} → {target}")

    def test_deferred_project_and_platform_dependencies_are_rejected(self):
        """通过延迟回调加入生产、demo 或平台越界依赖后检查，断言不解析制品也能报告实际声明配置。"""
        for module, configuration, action, dependency, message in (
            (
                "app",
                "implementation",
                "withDependencies",
                "project.dependencies.project(path: ':core-bridge')",
                ":app → implementation → :core-bridge",
            ),
            (
                "app",
                "demoImplementation",
                "defaultDependencies",
                "project.dependencies.project(path: ':vpn')",
                ":app → demoImplementation → :vpn",
            ),
            (
                "contracts",
                "api",
                "withDependencies",
                "project.dependencies.create('androidx.core:core:1')",
                "不能依赖 Android 平台库",
            ),
        ):
            with (
                self.subTest(module=module, action=action),
                TemporaryDirectory() as directory,
            ):
                root = Path(directory)
                self.fixture(root)
                with (root / module / "build.gradle").open("a") as stream:
                    stream.write(
                        f"\nconfigurations.{configuration}.{action} {{ dependencies ->\n"
                        f"  dependencies.add({dependency})\n}}\n"
                    )
                self.assert_blocked(root, message)

    def test_callback_mutating_another_configuration_is_rejected(self):
        """后创建配置的回调向已有生产配置加入 bridge 依赖时检查，断言采集顺序不能漏过越界。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            with (root / "app/build.gradle").open("a") as stream:
                stream.write(
                    "\nconfigurations.create('lateDeclarations').withDependencies { ignored ->\n"
                    "  project.configurations.implementation.dependencies.add(\n"
                    "    project.dependencies.project(path: ':core-bridge'))\n}\n"
                )
            self.assert_blocked(root, ":app → implementation → :core-bridge")

    def test_android_manual_resolvable_self_dependency_is_rejected(self):
        """Android 模块手工创建可解析生产配置或仿测试名称并声明自依赖，断言不能获得 AGP 组件豁免。"""
        for configuration in ("manualProduction", "debugUnitTestCompileClasspath"):
            with (
                self.subTest(configuration=configuration),
                TemporaryDirectory() as directory,
            ):
                root = Path(directory)
                self.fixture(root)
                self.android_plugin(root, "com.android.application")
                with (root / "app/build.gradle").open("a") as stream:
                    stream.write(
                        "\napply plugin: 'com.android.application'\n"
                        f"configurations {{ {configuration} {{\n"
                        "  canBeResolved = true; canBeConsumed = false\n}}\n"
                        f"configurations.implementation.extendsFrom(configurations.{configuration})\n"
                        f"dependencies {{ {configuration} project(':app') }}\n"
                    )
                self.assert_blocked(root, f":app → {configuration} → :app")

    def test_reverse_and_self_dependencies_are_rejected(self):
        """契约或 VPN 反向依赖 app，以及手工声明自依赖时检查，断言均不能作为合法边通过。"""
        for module, target in (("contracts", ":app"), ("vpn", ":app"), ("app", ":app")):
            with (
                self.subTest(module=module, target=target),
                TemporaryDirectory() as directory,
            ):
                root = Path(directory)
                self.fixture(root)
                with (root / module / "build.gradle").open("a") as stream:
                    stream.write(
                        f"dependencies {{ implementation project('{target}') }}\n"
                    )
                self.assert_blocked(root, f":{module} → implementation → {target}")

    def test_missing_required_edge_is_rejected(self):
        """删除 bridge 到契约的必要依赖后检查，断言缺失边不能被其他模块的传递关系补足。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            (root / "core-bridge/build.gradle").write_text(
                "plugins { id 'java-library' }\n"
            )
            self.assert_blocked(root, ":core-bridge → api → :contracts: 缺少必要依赖")

    def test_android_platform_dependencies_and_missing_jvm_plugin_are_rejected(self):
        """纯 JVM 模块声明 Android/AndroidX 制品或失去 JVM 插件时检查，断言不解析制品也会失败。"""
        for module, addition, message in (
            (
                "contracts",
                "dependencies { api 'androidx.core:core:1' }",
                "不能依赖 Android 平台库",
            ),
            (
                "core-bridge",
                "dependencies { implementation 'com.google.android.gms:synthetic:1' }",
                "不能依赖 Android 平台库",
            ),
            ("contracts", None, "必须使用 JVM library 插件"),
        ):
            with (
                self.subTest(module=module, addition=addition),
                TemporaryDirectory() as directory,
            ):
                root = Path(directory)
                self.fixture(root)
                build = root / module / "build.gradle"
                if addition is None:
                    build.write_text("")
                else:
                    with build.open("a") as stream:
                        stream.write(addition + "\n")
                self.assert_blocked(root, message)

    def test_android_plugin_is_rejected_even_without_android_sources(self):
        """合成 Android 插件不添加平台依赖时应用到 bridge，断言插件身份本身仍被阻止。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            self.android_plugin(root, "com.android.library")
            with (root / "core-bridge/build.gradle").open("a") as stream:
                stream.write("apply plugin: 'com.android.library'\n")
            self.assert_blocked(root, "纯 JVM 模块不能使用 Android 插件")


if __name__ == "__main__":
    unittest.main()
