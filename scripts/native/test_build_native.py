"""正式 native 输入与对齐门槛的负向回归，不执行 VPN 或继承设备验收。"""

from __future__ import annotations

import json
import subprocess
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import build_native


class NativeBuildContractTest(unittest.TestCase):
    def test_jni_entry_requires_current_package_and_exact_symbol(self) -> None:
        """新包名的完整导出可通过；只有旧入口、相似后缀或空符号表时拒绝。"""
        current = "Java_com_github_zzwtsy_chuanmen_lib_vpn_NativeBindings_request"
        self.assertTrue(
            build_native.has_jni_entry(f"001 FUNC GLOBAL DEFAULT 12 {current}")
        )
        for symbols in (
            "Java_com_github_zzwtsy_chuanmen_NativeBindings_request",
            current + "_extra",
            "",
        ):
            with self.subTest(symbols=symbols):
                self.assertFalse(build_native.has_jni_entry(symbols))

    def test_cached_checkout_must_match_current_patch(self) -> None:
        """缓存后像符合当前补丁时可复用；修改补丁后像或损坏格式必须拒绝，末尾空行不改变语义，相同覆盖文件不被重写。"""
        for change in ("unchanged", "trailing_newline", "changed", "malformed"):
            with self.subTest(change=change), TemporaryDirectory() as directory:
                root = Path(directory)
                checkout = root / "app/build/native/upstream"
                (checkout / ".git").mkdir(parents=True)
                (checkout / "core.txt").write_text("patched\n")
                for source in build_native.OVERLAYS:
                    path = root / source
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text("synthetic Android overlay\n")
                    (checkout / build_native.OVERLAYS[source]).parent.mkdir(
                        parents=True, exist_ok=True
                    )
                patch_file = root / "app/native/patches/session-host.diff"
                patch_file.parent.mkdir(parents=True, exist_ok=True)
                patch_text = (
                    "--- a/core.txt\n+++ b/core.txt\n@@ -1 +1 @@\n-original\n+patched\n"
                )
                if change == "trailing_newline":
                    patch_text += "\n"
                elif change == "changed":
                    patch_text = patch_text.replace("+patched", "+different")
                elif change == "malformed":
                    patch_text = "not a patch\n"
                patch_file.write_text(patch_text)
                with (
                    patch.object(build_native, "ROOT", root),
                    patch.object(build_native, "capture", return_value=" M core.txt"),
                    patch.object(build_native, "verify_checkout"),
                ):
                    if change in {"changed", "malformed"}:
                        with self.assertRaises(subprocess.CalledProcessError):
                            build_native.prepare({}, False)
                    else:
                        self.assertEqual(checkout, build_native.prepare({}, False))
                        timestamps = {
                            name: (checkout / name).stat().st_mtime_ns
                            for name in build_native.OVERLAYS.values()
                        }
                        build_native.prepare({}, False)
                        self.assertEqual(
                            timestamps,
                            {
                                name: (checkout / name).stat().st_mtime_ns
                                for name in build_native.OVERLAYS.values()
                            },
                        )

    def fixture(self, root: Path) -> dict:
        for name in (
            "app/native/src/lib.rs",
            "app/native/host/android_tun.rs",
            "app/native/patches/session-host.diff",
            "app/native/Cargo.toml",
            "app/native/Cargo.lock",
            "scripts/native/build_native.py",
        ):
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("synthetic-input")
        record = {
            "schema_version": 1,
            "core_commit": "a" * 40,
            "protocol": 1,
            "abis": list(build_native.TARGETS),
            "api": 24,
        }
        (root / "app/native/inputs.json").write_text(json.dumps(record))
        return record

    def test_actual_source_changes_invalidate_fingerprint_without_manual_manifest(
        self,
    ) -> None:
        """修改或新增原生源码都会改变动态输入身份，无需维护自有源码哈希清单。"""
        for change in ("modified", "added"):
            with self.subTest(change=change), TemporaryDirectory() as directory:
                root = Path(directory)
                record = self.fixture(root)
                self.assertEqual(record, build_native.inputs(root))
                before = build_native.input_fingerprint(root)
                name = "lib.rs" if change == "modified" else "unregistered.rs"
                (root / "app/native/src" / name).write_text("changed")
                self.assertNotEqual(before, build_native.input_fingerprint(root))
                self.assertEqual(record, build_native.inputs(root))

    def test_test_sources_do_not_invalidate_library(self) -> None:
        """只新增或修改 Rust 测试时，正式动态库的输入身份保持不变。"""
        with TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            before = build_native.input_fingerprint(root)
            tests = root / "app/native/tests"
            tests.mkdir()
            (tests / "contract.rs").write_text("synthetic test")
            self.assertEqual(before, build_native.input_fingerprint(root))
            (tests / "contract.rs").write_text("changed test")
            self.assertEqual(before, build_native.input_fingerprint(root))

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
        """报告缺少 ABI、来源过期、失败或库被改写时，不能通过正式产物检查。"""
        for change in ("abi", "identity", "failed", "library", "alignment"):
            with self.subTest(change=change), TemporaryDirectory() as directory:
                root = Path(directory)
                self.fixture(root)
                libraries = []
                for abi in build_native.TARGETS:
                    path = root / f"app/build/native/jni/{abi}/libchuanmen_core.so"
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
                    "source_sha256": build_native.input_fingerprint(root),
                    "libraries": libraries,
                }
                report_path = root / "app/build/native/build-report.json"
                report_path.write_text(json.dumps(report))
                with patch.object(build_native, "ROOT", root):
                    build_native.verify_artifacts()
                    if change == "abi":
                        report["libraries"] = libraries[:1]
                    elif change == "identity":
                        report["source_sha256"] = "stale"
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
