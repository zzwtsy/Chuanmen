"""正式 native 输入与对齐门槛的负向回归，不执行 VPN 或继承设备验收。"""

from __future__ import annotations

import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import build_native


class NativeBuildContractTest(unittest.TestCase):
    def fixture(self, root: Path) -> dict:
        files = {}
        for name in (
            "native/src/lib.rs",
            "native/host/android_tun.rs",
            "native/patches/session-host.diff",
            "native/Cargo.toml",
            "native/Cargo.lock",
            "scripts/native/build_native.py",
        ):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("synthetic-input")
            files[name] = build_native.digest(path)
        record = {"core_commit": build_native.COMMIT, "protocol": 1, "files": files}
        (root / "native/inputs.json").write_text(json.dumps(record))
        return record

    def test_source_hash_and_file_set_are_both_required(self) -> None:
        """固定输入核对通过后修改已有源码或增加未登记源码，两种情况均拒绝构建。"""
        for change in ("modified", "added"):
            with self.subTest(change=change), TemporaryDirectory() as directory:
                root = Path(directory)
                record = self.fixture(root)
                self.assertEqual(record, build_native.inputs(root))
                name = "lib.rs" if change == "modified" else "unregistered.rs"
                (root / "native/src" / name).write_text("changed")
                with self.assertRaises(ValueError):
                    build_native.inputs(root)

    def test_every_load_segment_must_be_aligned(self) -> None:
        """多段 ELF 中任一 LOAD 小于 16 KB 或缺少 LOAD 时拒绝，不只检查首段。"""
        valid = "LOAD 0x0000 0x0000 0x0000 0x0001 0x0001 R E 0x4000"
        self.assertTrue(build_native.load_segments_aligned(valid))
        self.assertFalse(
            build_native.load_segments_aligned(
                valid + "\nLOAD 0x0 0x0 0x0 0x1 0x1 RW 0x1000"
            )
        )
        self.assertFalse(build_native.load_segments_aligned("no segments"))

    def test_artifact_report_requires_both_current_abis(self) -> None:
        """报告缺少 ABI、来源过期、失败或库被改写时，不能解锁 live 构建。"""
        for change in ("abi", "identity", "failed", "library", "alignment"):
            with self.subTest(change=change), TemporaryDirectory() as directory:
                root = Path(directory)
                self.fixture(root)
                libraries = []
                for abi in build_native.TARGETS:
                    path = root / f"build/native/jni/{abi}/libchuanmen_core.so"
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b"synthetic-library")
                    libraries.append(
                        {
                            "abi": abi,
                            "path": str(path.relative_to(root)),
                            "sha256": build_native.digest(path),
                            "elf_16kb_aligned": True,
                        }
                    )
                report = {
                    "status": "built",
                    "inputs_sha256": build_native.digest(root / "native/inputs.json"),
                    "libraries": libraries,
                }
                report_path = root / "build/native/build-report.json"
                report_path.write_text(json.dumps(report))
                with patch.object(build_native, "ROOT", root):
                    build_native.verify_artifacts()
                    if change == "abi":
                        report["libraries"] = libraries[:1]
                    elif change == "identity":
                        report["inputs_sha256"] = "stale"
                    elif change == "failed":
                        report["status"] = "failed"
                    elif change == "alignment":
                        libraries[0]["elf_16kb_aligned"] = False
                    else:
                        (root / libraries[0]["path"]).write_bytes(b"changed-library")
                    report_path.write_text(json.dumps(report))
                    with self.assertRaises(ValueError):
                        build_native.verify_artifacts()


if __name__ == "__main__":
    unittest.main()
