"""PR 元数据、真实 Git 差异和 CI 失败传播的回归。"""

from __future__ import annotations

import copy
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

import workflow_policy as policy


class WorkflowPolicyTest(unittest.TestCase):
    def test_pr_titles(self):
        """中文、scope、破坏性标记通过；错误类型、空描述和非 main 失败。"""
        event = {"pull_request": {"title": "", "base": {"ref": "main"}}}
        for title in (
            "feat: 增加网络列表",
            "fix(profile): 修复保存",
            "refactor(core)!: 调整契约",
            "build!: 升级工具链",
            "docs: " + "说明" * 100,
        ):
            with self.subTest(title=title):
                event["pull_request"]["title"] = title
                policy.validate_pr(event)
        for title in ("更新网络列表", "feature: 新增", "fix: ", "fix : 问题"):
            with self.subTest(title=title):
                event["pull_request"]["title"] = title
                with self.assertRaises(ValueError):
                    policy.validate_pr(event)
        event["pull_request"].update(title="docs: 说明", base={"ref": "develop"})
        with self.assertRaises(ValueError):
            policy.validate_pr(event)

    def test_cli_treats_title_as_data(self):
        """带命令替换与换行的标题仅作 JSON 数据；CLI 不创建命令目标文件。"""
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            marker = root / "executed"
            event_file = root / "event.json"
            env = {**os.environ, "GITHUB_EVENT_PATH": str(event_file)}
            for title, exit_code in (
                (f"docs: $(touch {marker}) `touch {marker}` ; & | >", 0),
                (f"docs: 说明\ntouch {marker}", 1),
            ):
                with self.subTest(title=title):
                    event_file.write_text(
                        json.dumps(
                            {"pull_request": {"title": title, "base": {"ref": "main"}}}
                        ),
                        encoding="utf-8",
                    )
                    result = subprocess.run(
                        [sys.executable, str(Path(policy.__file__)), "pr"],
                        env=env,
                        capture_output=True,
                        check=False,
                    )
                    self.assertEqual(result.returncode, exit_code, result.stderr)
                    self.assertFalse(marker.exists())

    def test_change_classification(self):
        """已知文档省略 Android；构建、配置、CI 和未知路径必须全检。"""
        for path in (
            "docs/development/git-workflow.md",
            ".agents/skills/example/SKILL.md",
            "prototype/index.html",
            "experiments/probe/main.rs",
            "README.md",
            "AGENTS.md",
            "CONTRIBUTING.md",
        ):
            with self.subTest(path=path):
                self.assertFalse(policy.android_required([path]))
        for path in (
            "app/src/main/AndroidManifest.xml",
            "contracts/build.gradle.kts",
            "gradle/libs.versions.toml",
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle.properties",
            "gradlew",
            "gradlew.bat",
            "scripts/android/build-demo.sh",
            "scripts/catalog/audit_config_catalog.py",
            "docs/design/config/android-capability-plan.json",
            ".github/workflows/ci.yml",
            ".github/dependabot.yml",
            "ruff.toml",
            "new-module/src/main.kt",
            "scripts/git/workflow_policy.py",
        ):
            with self.subTest(path=path):
                self.assertTrue(policy.android_required(["README.md", path]))

    def test_p02_ci_inputs_cannot_skip_android(self):
        """P02 阶段工具、对端或审查输入单独变化时分类，断言不能作为文档省略阶段 CI。"""
        for path in (
            "experiments/p02-vpn-core/check_review.py",
            "experiments/p02-vpn-core/test_check_review.py",
            "experiments/p02-vpn-core/review.py",
            "experiments/p02-vpn-core/test_review.py",
            "experiments/p02-vpn-core/native-peer/src/main.rs",
            "docs/verification/p02/native-review.json",
            "docs/verification/p02/native-review.md",
        ):
            with self.subTest(path=path):
                self.assertTrue(policy.android_required(["README.md", path]))

    def test_gate_results(self):
        """完整成功或明确文档免检通过；失败、取消、缺失和意外跳过失败。"""
        for required, android_result in (("true", "success"), ("false", "skipped")):
            needs = {
                "changes": {
                    "result": "success",
                    "outputs": {"android_required": required},
                },
                "verification": {"result": "success"},
                "android": {"result": android_result},
            }
            policy.validate_gate(needs)
            for job in ("changes", "verification", "android"):
                for result in ("failure", "cancelled", "", "skipped", "success"):
                    if result == needs[job]["result"]:
                        continue
                    with self.subTest(required=required, job=job, result=result):
                        invalid = copy.deepcopy(needs)
                        invalid[job]["result"] = result
                        with self.assertRaises(ValueError):
                            policy.validate_gate(invalid)
                invalid = copy.deepcopy(needs)
                del invalid[job]
                with self.assertRaises(KeyError):
                    policy.validate_gate(invalid)
            for required_output in (None, "", "TRUE", True):
                invalid = copy.deepcopy(needs)
                invalid["changes"]["outputs"]["android_required"] = required_output
                with self.assertRaises(ValueError):
                    policy.validate_gate(invalid)

    def test_invalid_events_and_cli_exit(self):
        """事件损坏与门禁数据缺失返回非零，不把异常包装为成功。"""
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            event_file = root / "event.json"
            for content in ("[]", "{broken", "{}"):
                event_file.write_text(content, encoding="utf-8")
                result = subprocess.run(
                    [sys.executable, str(Path(policy.__file__)), "pr"],
                    env={**os.environ, "GITHUB_EVENT_PATH": str(event_file)},
                    capture_output=True,
                    check=False,
                )
                self.assertNotEqual(result.returncode, 0)
            result = subprocess.run(
                [sys.executable, str(Path(policy.__file__)), "gate"],
                env={**os.environ, "NEEDS_JSON": "{}"},
                capture_output=True,
                check=False,
            )
            self.assertNotEqual(result.returncode, 0)

    def test_git_deletion_rename_and_merge_base(self):
        """真实 Git 删除/双向重命名保留原路径；主干独有变更不计入 PR。"""
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)

            def git(*args):
                return policy.git_output(root, *args).decode("ascii").strip()

            def write(path):
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text("fixture\n", encoding="utf-8")

            def commit():
                git("add", ".")
                git(
                    "-c",
                    "user.name=Workflow test",
                    "-c",
                    "user.email=workflow@example.invalid",
                    "-c",
                    "commit.gpgsign=false",
                    "commit",
                    "-qm",
                    "test: fixture",
                )
                return git("rev-parse", "HEAD")

            git("init", "-q", "--initial-branch=main")
            write("app/source.txt")
            write("docs/source.txt")
            write("contracts/deleted.txt")
            base = commit()
            git("switch", "-qc", "task")
            git("mv", "app/source.txt", "docs/from-app.txt")
            git("mv", "docs/source.txt", "app/from-docs.txt")
            git("rm", "-q", "contracts/deleted.txt")
            head = commit()
            git("switch", "-q", "main")
            write("docs/main-only.md")
            current_base = commit()
            event = {
                "pull_request": {"base": {"sha": current_base}, "head": {"sha": head}}
            }
            paths = policy.changed_paths(event, "pull_request", root)
            self.assertEqual(
                set(paths),
                {
                    "app/source.txt",
                    "docs/from-app.txt",
                    "docs/source.txt",
                    "app/from-docs.txt",
                    "contracts/deleted.txt",
                },
            )
            self.assertTrue(policy.android_required(paths))
            event = {"ref": "refs/heads/main", "before": base, "after": current_base}
            self.assertEqual(
                policy.changed_paths(event, "push", root), ["docs/main-only.md"]
            )
            # CLI 对纯文档输出 false；手动运行强制 true，坏 SHA 返回非零。
            event_file = root / "event.json"
            output_file = root / "output.txt"
            for event_name, payload, expected in (
                ("push", event, "android_required=false\n"),
                ("workflow_dispatch", {}, "android_required=true\n"),
                ("push", {**event, "before": "bad-sha"}, ""),
            ):
                with self.subTest(event_name=event_name, expected=expected):
                    event_file.write_text(json.dumps(payload), encoding="utf-8")
                    output_file.write_text("", encoding="utf-8")
                    result = subprocess.run(
                        [sys.executable, str(Path(policy.__file__)), "changes"],
                        cwd=root,
                        env={
                            **os.environ,
                            "GITHUB_EVENT_PATH": str(event_file),
                            "GITHUB_EVENT_NAME": event_name,
                            "GITHUB_OUTPUT": str(output_file),
                        },
                        capture_output=True,
                        check=False,
                    )
                    self.assertEqual(result.returncode, 0 if expected else 1)
                    self.assertEqual(output_file.read_text(encoding="utf-8"), expected)
            event["before"] = "0" * 40
            self.assertIsNone(policy.changed_paths(event, "push", root))
            self.assertIsNone(policy.changed_paths({}, "workflow_dispatch", root))
            event["before"] = "$(touch invalid)"
            with self.assertRaises(ValueError):
                policy.changed_paths(event, "push", root)
            with self.assertRaises(ValueError):
                policy.changed_paths({}, "unsupported", root)


if __name__ == "__main__":
    unittest.main()
