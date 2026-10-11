"""核对真实 APK 的库哈希和 DEX 类定义；静态检查不替代设备执行。"""

import argparse
import hashlib
import json
import struct
import zipfile
from datetime import UTC, datetime
from pathlib import Path


def sha(data):
    return hashlib.sha256(data).hexdigest()


def classes(data):
    """按 DEX 表读取类描述符；只统计 class_def，不把字符串引用当已包含类。"""
    if len(data) < 112 or not data.startswith(b"dex\n"):
        raise ValueError("不符合标准 DEX 头")

    def uint(offset):
        return struct.unpack_from("<I", data, offset)[0]

    count, offset = uint(96), uint(100)
    strings, types = uint(60), uint(68)
    result = []
    for index in range(count):
        class_index = uint(offset + index * 32)
        string_index = uint(types + class_index * 4)
        start = uint(strings + string_index * 4)
        while data[start] & 0x80:
            start += 1
        start += 1  # 跳过 ULEB128 UTF-16 长度；类描述符为 ASCII。
        end = data.index(b"\0", start)
        result.append(data[start:end].decode("ascii"))
    return sorted(result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-report", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        parser.error("禁止覆盖已有原件")
    build = json.loads(args.build_report.read_text())
    if build.get("status") != "passed":
        parser.error("要求真实成功 APK 构建报告")
    apk = Path(build["apk_path"])
    if sha(apk.read_bytes()) != build["apk_sha256"]:
        parser.error("APK 哈希不符")
    entries = []
    definitions = {}
    with zipfile.ZipFile(apk) as archive:
        for member in archive.infolist():
            data = archive.read(member)
            entries.append(
                {"name": member.filename, "bytes": len(data), "sha256": sha(data)}
            )
            if member.filename.endswith(".dex"):
                definitions[member.filename] = classes(data)
    hashes = {entry["name"]: entry["sha256"] for entry in entries}
    library_checks = {
        lib["abi"]: hashes.get("lib/" + lib["abi"] + "/libeasytier_android_jni.so")
        == lib["sha256"]
        for lib in build["libraries"]
    }
    unexpected = [
        name
        for values in definitions.values()
        for name in values
        if not name.startswith(("Lcom/easytier/jni/", "Lcom/github/zzwtsy/chuanmen/"))
    ]
    archives = [
        entry["name"] for entry in entries if entry["name"].endswith((".aar", ".jar"))
    ]
    report = {
        "layer": "apk_static_structure",
        "status": "passed"
        if definitions
        and all(library_checks.values())
        and not unexpected
        and not archives
        else "failed",
        "recorded_at_utc": datetime.now(UTC).isoformat(),
        "runner_sha256": sha(Path(__file__).read_bytes()),
        "build_report_sha256": sha(args.build_report.read_bytes()),
        "apk_sha256": build["apk_sha256"],
        "entries": entries,
        "dex_class_definitions": definitions,
        "native_library_hash_matches": library_checks,
        "unexpected_class_definitions": unexpected,
        "embedded_aar_or_jar": archives,
        "verifier_jvm_class_definitions_present": any(
            "rustls" in name.lower()
            for values in definitions.values()
            for name in values
        ),
        "android_runtime_status": "unverified",
        "meaning": "当前 APK 静态类定义仅自有实验及JNI声明；没有合入 verifier AAR。不能据此宣称平台TLS/QUIC互操作通过。",
    }
    args.out.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(
        json.dumps(
            {"status": report["status"], "report": str(args.out)}, ensure_ascii=False
        )
    )
    return 0 if report["status"] == "passed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
