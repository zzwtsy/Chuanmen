"""APK 打包字节门槛的负向回归。"""

import hashlib
import struct
import unittest
import zipfile
from pathlib import Path
from tempfile import TemporaryDirectory

from check_apk import aligned_elf, packaged_libraries


def elf(abi, alignment=16384):
    data = bytearray(120)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into("<HH", data, 16, 3, 183 if abi == "arm64-v8a" else 62)
    struct.pack_into("<Q", data, 32, 64)
    struct.pack_into("<HHH", data, 52, 64, 56, 1)
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, 0, 0, 120, 120, alignment)
    return bytes(data)


class PackagedNativeTest(unittest.TestCase):
    def test_missing_changed_compressed_duplicate_and_extra_library_are_rejected(self):
        """正确双 ABI 通过；任一缺项、篡改、压缩、重复或额外库均不能解锁正式 APK。"""
        for change in (
            "valid",
            "missing",
            "changed",
            "compressed",
            "duplicate",
            "extra",
        ):
            with self.subTest(change=change), TemporaryDirectory() as directory:
                apk = Path(directory) / "synthetic.apk"
                libraries = [
                    {"abi": abi, "sha256": hashlib.sha256(elf(abi)).hexdigest()}
                    for abi in ("arm64-v8a", "x86_64")
                ]
                with zipfile.ZipFile(apk, "w") as archive:
                    for lib in libraries:
                        if change == "missing" and lib["abi"] == "x86_64":
                            continue
                        name = f"lib/{lib['abi']}/libchuanmen_core.so"
                        data = b"changed" if change == "changed" else elf(lib["abi"])
                        kind = (
                            zipfile.ZIP_DEFLATED
                            if change == "compressed"
                            else zipfile.ZIP_STORED
                        )
                        archive.writestr(name, data, compress_type=kind)
                    if change == "duplicate":
                        with self.assertWarns(UserWarning):
                            archive.writestr(
                                "lib/x86_64/libchuanmen_core.so", b"x86_64"
                            )
                    if change == "extra":
                        archive.writestr("lib/x86_64/libunexpected.so", b"extra")
                if change == "valid":
                    self.assertEqual(
                        libraries, packaged_libraries(apk, {"libraries": libraries})
                    )
                else:
                    with self.assertRaises(ValueError):
                        packaged_libraries(apk, {"libraries": libraries})

    def test_elf_segment_bounds_architecture_and_alignment_are_checked(self):
        """两个 ABI 的实际 LOAD 布局可通过；4 KB、坏架构、缺段或越界文件均拒绝。"""
        for abi in ("arm64-v8a", "x86_64"):
            self.assertTrue(aligned_elf(elf(abi), abi))
            for data in (
                elf(abi, 4096),
                elf(abi)[:100],
                b"not ELF",
                elf("x86_64" if abi == "arm64-v8a" else "arm64-v8a"),
            ):
                with self.subTest(abi=abi, data=data[:20]):
                    self.assertFalse(aligned_elf(data, abi))

    def test_third_party_library_cannot_bypass_packaged_elf_gate(self):
        """核心双 ABI 正确，但第三方库只有 4 KB LOAD 对齐时，仍拒绝整个 APK。"""
        with TemporaryDirectory() as directory:
            apk = Path(directory) / "synthetic.apk"
            libraries = [
                {"abi": abi, "sha256": hashlib.sha256(elf(abi)).hexdigest()}
                for abi in ("arm64-v8a", "x86_64")
            ]
            with zipfile.ZipFile(apk, "w") as archive:
                for lib in libraries:
                    archive.writestr(
                        f"lib/{lib['abi']}/libchuanmen_core.so", elf(lib["abi"])
                    )
                archive.writestr(
                    "lib/x86_64/libandroidx.graphics.path.so", elf("x86_64", 4096)
                )
            with self.assertRaisesRegex(ValueError, "LOAD"):
                packaged_libraries(apk, {"libraries": libraries})


if __name__ == "__main__":
    unittest.main()
