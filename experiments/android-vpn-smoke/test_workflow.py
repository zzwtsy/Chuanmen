"""离线验证夹具、APK assets 和导出后的复建闭包；不编译 native 或操作设备。"""

import hashlib
import importlib.util
import io
import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]


def load_builder():
    spec = importlib.util.spec_from_file_location(
        "build_probe", ROOT / "support/build_probe.py"
    )
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="chuanmen-workflow-tests-")
        self.addCleanup(self.folder.cleanup)
        self.temp = Path(self.folder.name)

    def invoke(self, script, *args):
        return subprocess.run(
            [sys.executable, "-B", str(script), *map(str, args)],
            check=False,
            cwd=self.temp,
            capture_output=True,
            text=True,
            timeout=30,
        )

    def exported(self):
        destination = self.temp / "export"
        declaration = json.loads((ROOT / "experiment.json").read_bytes())
        exporter = REPO / declaration["reproduction"]["inputs"]["source_exporter"]
        result = self.invoke(exporter, "--experiment", ROOT.name, "--out", destination)
        self.assertEqual(result.returncode, 0, result.stderr)
        return destination, destination / "experiments" / ROOT.name

    def test_fixture_generation_preserves_all_cases_and_bytes(self):
        """条件：原冻结映射；操作：仓库外生成夹具；结果：88案例、82预期接受及原字节保持。"""
        generated = self.temp / "cases.json"
        result = self.invoke(
            ROOT / "config-roundtrip/make_cases.py", "--out", generated
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            generated.read_bytes(),
            (ROOT / "config-roundtrip/fixtures/config-cases.json").read_bytes(),
        )
        cases = json.loads(generated.read_bytes())["cases"]
        self.assertEqual(len(cases), 88)
        self.assertEqual(sum(c["expected_acceptance"] for c in cases), 82)

    def test_parser_apk_asset_uses_the_shared_fixture_bytes(self):
        """条件：宿主夹具单一来源；操作：写入合成ZIP；结果：解析assets一致，边界探针不携带。"""
        builder = load_builder()
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            builder.write_assets(archive, builder.probe_assets(False))
        with zipfile.ZipFile(buffer) as archive:
            self.assertEqual(archive.namelist(), ["assets/config-cases.json"])
            self.assertEqual(
                archive.read("assets/config-cases.json"),
                (ROOT / "config-roundtrip/fixtures/config-cases.json").read_bytes(),
            )
        self.assertEqual(builder.probe_assets(True), {})

    def test_relocated_patch_sources_keep_declared_identity(self):
        """条件：补丁源码迁移；操作：与声明输入重算摘要；结果：两个自有Rust源码身份一致。"""
        declaration = json.loads((ROOT / "experiment.json").read_bytes())
        patch = json.loads(
            (
                REPO / declaration["reproduction"]["inputs"]["native_boundary_patch"]
            ).read_bytes()
        )
        for path, identity in patch["files"].items():
            if identity["original_sha256"] is None:
                source = ROOT / "vpn-runtime/native" / Path(path).name
                self.assertEqual(
                    hashlib.sha256(source.read_bytes()).hexdigest(),
                    identity["patched_sha256"],
                )

    def test_exported_workflow_uses_declared_inputs_and_does_not_execute(self):
        """条件：导出后另置声明输入；操作：预览和夹具复算；结果：闭包独立可读且不创建运行目录。"""
        repo, exp = self.exported()
        declaration_path = exp / "experiment.json"
        declaration = json.loads(declaration_path.read_bytes())
        for role in ("config_mapping", "native_boundary_patch"):
            name = declaration["reproduction"]["inputs"][role]
            replacement = repo / "inputs" / (role + ".json")
            replacement.parent.mkdir(exist_ok=True)
            (repo / name).rename(replacement)
            declaration["reproduction"]["inputs"][role] = replacement.relative_to(
                repo
            ).as_posix()
        declaration_path.write_text(json.dumps(declaration, ensure_ascii=False))
        run = self.temp / "preview-run"
        before = set(repo.rglob("*"))
        result = self.invoke(exp / "rebuild.py", "--dry-run", "--root", run)
        self.assertEqual(result.returncode, 0, result.stderr)
        preview = json.loads(result.stdout)
        self.assertFalse(preview["experiment_executed"])
        self.assertFalse(preview["network_started"])
        self.assertEqual(len(preview["steps"]), 7)
        self.assertFalse(run.exists())
        self.assertEqual(set(repo.rglob("*")), before)
        self.assertTrue(
            all((repo / path).is_file() for path in preview["source_files"])
        )
        self.assertIn("inputs/config_mapping.json", preview["source_files"])
        self.assertIn("inputs/native_boundary_patch.json", preview["source_files"])
        generated = self.temp / "exported-cases.json"
        result = self.invoke(exp / "config-roundtrip/make_cases.py", "--out", generated)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            generated.read_bytes(),
            (exp / "config-roundtrip/fixtures/config-cases.json").read_bytes(),
        )

    def test_missing_exported_input_stops_before_creating_run(self):
        """条件：导出输入缺失；操作：预览复建；结果：非零退出且无半份运行目录。"""
        repo, exp = self.exported()
        declaration = json.loads((exp / "experiment.json").read_bytes())
        (repo / declaration["reproduction"]["inputs"]["native_boundary_patch"]).unlink()
        run = self.temp / "preview-run"
        result = self.invoke(exp / "rebuild.py", "--dry-run", "--root", run)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(run.exists())

    def test_exported_cli_help_has_no_execution_side_effects(self):
        """条件：源码闭包位于仓库外；操作：所有CLI请求帮助；结果：入口可加载且未写运行文件。"""
        repo, exp = self.exported()
        before = set(repo.rglob("*"))
        scripts = [
            path
            for path in exp.rglob("*.py")
            if path.name not in ("test_workflow.py", "benchmark_endpoints.py")
        ]
        for path in scripts:
            with self.subTest(entry=path.relative_to(exp).as_posix()):
                result = self.invoke(path, "--help")
                self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(set(repo.rglob("*")), before)


if __name__ == "__main__":
    unittest.main()
