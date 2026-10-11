"""用 CLI 正例和故障输入验证审计退出码、差异边界及输入不被覆盖。"""

import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from copy import deepcopy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path(__file__).resolve().with_name("audit_config_catalog.py")
BASELINE = ROOT / "docs/design/config/assistant-catalog.snapshot.json"


class AuditCliTests(unittest.TestCase):
    def setUp(self) -> None:
        self.directory = tempfile.TemporaryDirectory(prefix="easytier-audit-")
        self.addCleanup(self.directory.cleanup)
        self.temp = Path(self.directory.name)
        self.catalog = json.loads(BASELINE.read_text(encoding="utf-8"))

    def write_catalog(self, catalog: dict) -> Path:
        path = self.temp / "candidate.json"
        path.write_text(json.dumps(catalog, ensure_ascii=False), encoding="utf-8")
        return path

    def invoke(self, *args: str | Path) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, str(SCRIPT), *map(str, args)],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_current_baseline(self) -> None:
        """冻结目录及能力计划有效时，默认 CLI 成功且明确不代表原生运行验证。"""
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        report = json.loads(result.stdout)
        self.assertEqual(report["status"], "PASS")
        self.assertTrue(report["capability_plan_checked"])
        self.assertIn("不是官网实时或原生运行验证", report["scope"])

    def test_duplicate_key(self) -> None:
        """声明数量同步增加也不能掩盖重复键，必须指出冲突键并退出 1。"""
        data = deepcopy(self.catalog)
        data["fields"].append(deepcopy(data["fields"][0]))
        data["meta"]["visibleCoreCount"] += 1
        data["meta"]["visibleFieldCount"] += 1
        result = self.invoke("--catalog", self.write_catalog(data))
        self.assertEqual(result.returncode, 1)
        self.assertTrue(
            any("重复配置键" in e for e in json.loads(result.stdout)["errors"])
        )

    def test_missing_source_mapping(self) -> None:
        """MTU 编辑器仍存在但来源映射丢失时，不能仅凭总数正确通过。"""
        data = deepcopy(self.catalog)
        next(f for f in data["fields"] if f["key"] == "flags.mtu")[
            "assistantFields"
        ] = []
        result = self.invoke("--catalog", self.write_catalog(data))
        self.assertEqual(result.returncode, 1)
        self.assertIn("mtu", " ".join(json.loads(result.stdout)["errors"]))

    def test_hidden_field_is_visible(self) -> None:
        """平台托管项误进可见编辑器时，显隐冲突必须有明确诊断。"""
        data = deepcopy(self.catalog)
        field = deepcopy(data["fields"][0])
        field.update(key="flags.no_tun", assistantFields=["no_tun"])
        data["fields"].append(field)
        data["meta"]["visibleCoreCount"] += 1
        data["meta"]["visibleFieldCount"] += 1
        result = self.invoke("--catalog", self.write_catalog(data))
        self.assertEqual(result.returncode, 1)
        self.assertTrue(
            any(
                "隐藏配置进入可见表单" in e for e in json.loads(result.stdout)["errors"]
            )
        )

    def test_valid_change_requires_review(self) -> None:
        """合法的默认值变化仍需评审，报告差异属性但不回显值。"""
        data = deepcopy(self.catalog)
        marker = "SYNTHETIC-CHANGE-NOT-FOR-OUTPUT"
        next(f for f in data["fields"] if f["key"] == "hostname")["default"] = marker
        result = self.invoke(
            "--catalog", self.write_catalog(data), "--baseline", BASELINE
        )
        self.assertEqual(result.returncode, 1)
        self.assertEqual(json.loads(result.stdout)["status"], "REVIEW_REQUIRED")
        self.assertFalse(json.loads(result.stdout)["capability_plan_checked"])
        self.assertNotIn(marker, result.stdout)

    def test_nested_child_change_requires_review(self) -> None:
        """复合节点子字段发生变化时，不能因为父字段类型未变而遗漏差异。"""
        data = deepcopy(self.catalog)
        field = next(f for f in data["fields"] if f["key"] == "peer")
        field["children"][0]["label"] = "合成的节点地址说明"
        result = self.invoke(
            "--catalog", self.write_catalog(data), "--baseline", BASELINE
        )
        self.assertEqual(result.returncode, 1)
        changes = json.loads(result.stdout)["differences"]
        self.assertTrue(
            any(
                c["key"] == "peer" and "children" in c.get("properties", [])
                for c in changes
            )
        )

    def test_explicit_capability_plan_hash_mismatch(self) -> None:
        """显式台账即使字段齐全，绑定了另一目录版本也必须失败。"""
        plan = json.loads(
            (ROOT / "docs/design/config/android-capability-plan.json").read_text(
                encoding="utf-8"
            )
        )
        plan["catalogSha256"] = "0" * 64
        path = self.temp / "capabilities.json"
        path.write_text(json.dumps(plan, ensure_ascii=False), encoding="utf-8")
        result = self.invoke("--capability-plan", path)
        self.assertEqual(result.returncode, 1)
        self.assertTrue(
            any("目录哈希不符" in e for e in json.loads(result.stdout)["errors"])
        )

    def test_missing_input(self) -> None:
        """文件不可读时退出 2，不能报告目录完整或退回默认快照。"""
        result = self.invoke("--catalog", self.temp / "missing.json")
        self.assertEqual(result.returncode, 2)
        self.assertEqual(json.loads(result.stdout)["status"], "INPUT_ERROR")

    def test_invalid_json(self) -> None:
        """JSON 被截断时输出输入错误，不能继续推测目录计数。"""
        path = self.temp / "broken.json"
        path.write_text('{"fields":', encoding="utf-8")
        result = self.invoke("--catalog", path)
        self.assertEqual(result.returncode, 2)

    def test_report_cannot_overwrite_input(self) -> None:
        """报告误指定为输入路径时失败，原目录字节必须保持不变。"""
        path = self.write_catalog(self.catalog)
        before = hashlib.sha256(path.read_bytes()).hexdigest()
        result = self.invoke("--catalog", path, "--report", path)
        self.assertEqual(result.returncode, 2)
        self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), before)


if __name__ == "__main__":
    unittest.main(verbosity=2)
