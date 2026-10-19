"""核对正式 APK 内双 ABI 库的实际字节和 ZIP/16 KB 对齐，不声明设备加载通过。"""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

import build_native


def aligned_elf(data: bytes, abi: str) -> bool:
    """实际打包的每个 ELF64 LOAD 都满足页对齐；第三方库也不能只检查 ZIP 对齐。"""
    if len(data) < 64 or data[:6] != b"\x7fELF\x02\x01":
        return False
    kind, machine = struct.unpack_from("<HH", data, 16)
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    if (
        kind != 3
        or machine != {"arm64-v8a": 183, "x86_64": 62}.get(abi)
        or entry_size < 56
        or count == 0
        or offset < 64
        or offset + count * entry_size > len(data)
    ):
        return False
    loads = 0
    for index in range(count):
        (
            segment,
            _,
            file_offset,
            virtual_address,
            _,
            file_size,
            memory_size,
            alignment,
        ) = struct.unpack_from("<IIQQQQQQ", data, offset + index * entry_size)
        if segment == 1:
            loads += 1
            if (
                alignment < 16384
                or alignment & (alignment - 1)
                or virtual_address % 16384 != file_offset % 16384
                or file_size > memory_size
                or file_offset + file_size > len(data)
            ):
                return False
    return loads > 0


def packaged_libraries(apk: Path, report: dict) -> list[dict]:
    """库必须完整、未压缩、无重复且与本次 native 产物相同；没有库也拒绝。"""
    libraries = report["libraries"]
    expected = {f"lib/{lib['abi']}/libchuanmen_core.so": lib for lib in libraries}
    if len(expected) != 2 or {lib["abi"] for lib in libraries} != set(
        build_native.TARGETS
    ):
        raise ValueError("APK 门槛需要本次双 ABI 输入")
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        native = [
            name for name in names if name.startswith("lib/") and name.endswith(".so")
        ]
        allowed = set(expected) | {
            f"lib/{abi}/libandroidx.graphics.path.so" for abi in build_native.TARGETS
        }
        if (
            not set(expected) <= set(native)
            or not set(native) <= allowed
            or len(native) != len(set(native))
        ):
            raise ValueError("APK 原生库集合或重复 entry 无效")
        for name in native:
            info = archive.getinfo(name)
            if info.compress_type != zipfile.ZIP_STORED:
                raise ValueError("APK 原生库未使用直接加载布局")
            data = archive.read(name)
            if not aligned_elf(data, name.split("/")[1]):
                raise ValueError("APK 实际 ELF 架构或 LOAD 段不满足 16 KB 对齐")
            if (
                name in expected
                and hashlib.sha256(data).hexdigest() != expected[name]["sha256"]
            ):
                raise ValueError("APK 打包库与本次 native 产物身份不同")
    return libraries


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--sdk", type=Path, required=True)
    args = parser.parse_args()
    try:
        build_native.inputs()
        build_native.verify_artifacts()
        report = json.loads(
            (build_native.ROOT / "build/native/build-report.json").read_text()
        )
        libraries = packaged_libraries(args.apk, report)
        subprocess.run(
            [
                str(args.sdk / "build-tools/36.0.0/zipalign"),
                "-c",
                "-P",
                "16",
                "4",
                str(args.apk),
            ],
            check=True,
        )
        print(
            f"LIVE_APK_OK: {len(libraries)} ABI 与本次 native 字节相同，ELF / APK 16 KB 对齐；不代表 16 KB runtime 验收"
        )
    except (
        OSError,
        ValueError,
        KeyError,
        zipfile.BadZipFile,
        subprocess.SubprocessError,
    ) as error:
        print(f"LIVE_APK_FAILED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
