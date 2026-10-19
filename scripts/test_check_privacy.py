"""隐私扫描和暂存隔离回归，全部使用合成样本。"""

import json
import subprocess
import tempfile
import unittest
from pathlib import Path

from check_privacy import Tree, findings, relative_name, scan


class PrivacyTest(unittest.TestCase):
    def test_nested_posix_windows_and_argv(self):
        """条件：嵌套POSIX/Windows位置和真实ADB参数；操作：扫描；结果：全部检出且不回显值。"""
        # 分段构造合成隐私样本，避免源码出现扫描器会拒绝的完整个人路径。
        personal = "/" + "/".join(("home", "synthetic-user", "sdk", "tool"))  # noqa: FLY002
        windows = "C:" + "\\" + "\\".join(("Users", "synthetic-user", "sdk"))  # noqa: FLY002
        value = {
            "commands": [
                {"argv": ["adb", "-s", "SYNTHETIC-SERIAL"], "stdout": personal}
            ],
            "nested": {"path": windows},
            "serial_sha256": "0" * 64,
        }
        found = findings(value)
        self.assertGreaterEqual(len(found), 4)
        self.assertNotIn("synthetic-user", json.dumps(found))
        self.assertNotIn("SYNTHETIC-SERIAL", json.dumps(found))

    def test_project_namespace_and_synthetic_hostname_survive(self):
        """条件：公开namespace和合成hostname；操作：扫描；结果：保留配置语义。"""
        value = {"package": "com.example.project", "hostname": "synthetic-peer"}
        self.assertEqual(findings(value), [])

    def test_uname_hostname_is_detected(self):
        """条件：uname含真实宿主名；操作：扫描；结果：发现宿主标识且不回显。"""
        value = {"system": {"uname": "Linux SYNTHETIC-HOST kernel-version x86_64"}}
        self.assertTrue(findings(value))
        self.assertNotIn("SYNTHETIC-HOST", json.dumps(findings(value)))

    def test_relative_path_and_symlink_escape(self):
        """条件：父路径或符号链接越界；操作：读取；结果：拒绝借用包外文件。"""
        with self.assertRaises(ValueError):
            relative_name("../outside")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "link").symlink_to(root.parent)
            with self.assertRaises(ValueError):
                Tree(root).read("link/outside")

    def test_index_does_not_borrow_worktree(self):
        """条件：暂存安全内容后工作树变化；操作：索引读取；结果：仍读取原暂存字节。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "-q", root], check=True)
            (root / "record.txt").write_text("staged")
            subprocess.run(["git", "-C", str(root), "add", "record.txt"], check=True)
            (root / "record.txt").write_text("unstaged")
            tree = Tree(root, "index")
            self.assertEqual(tree.read("record.txt"), b"staged")
            (root / "extra.txt").write_text("not staged")
            with self.assertRaises(ValueError):
                tree.read("extra.txt")

    def test_staged_scan_does_not_borrow_unsafe_worktree_bytes(self):
        """安全内容暂存后工作树加入合成凭据，扫描两棵树，断言仅工作树失败且诊断不泄漏凭据。"""
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(["git", "init", "-q", root], check=True)
            path = root / "record.txt"
            path.write_text("safe-staged-content")
            subprocess.run(["git", "-C", str(root), "add", "record.txt"], check=True)
            secret = "ghp_" + "a" * 36
            path.write_text(secret)
            self.assertEqual("passed", scan(Tree(root, "index"))["status"])
            report = scan(Tree(root))
            self.assertEqual("failed", report["status"])
            self.assertNotIn(secret, json.dumps(report))


if __name__ == "__main__":
    unittest.main()
