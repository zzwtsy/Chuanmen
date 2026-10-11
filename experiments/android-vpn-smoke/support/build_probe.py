"""把已核验的上游 JNI 库打包为解析探针；不修改核心，不启动 VPN。"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import zipfile
from datetime import UTC, datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORE = "7592951b808c4839cb9d77cc4bc642f124915951"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def probe_assets(boundary):
    """解析探针共享宿主夹具；边界探针不携带配置解析样本。"""
    return (
        {}
        if boundary
        else {"config-cases.json": ROOT / "config-roundtrip/fixtures/config-cases.json"}
    )


def write_assets(archive, assets):
    """把唯一来源的样本直接写入输出 APK，不在源码树复制 assets。"""
    for name, path in sorted(assets.items()):
        entry = zipfile.ZipInfo("assets/" + name, (2026, 1, 1, 0, 0, 0))
        entry.compress_type = zipfile.ZIP_DEFLATED
        archive.writestr(entry, path.read_bytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--native-build-report", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--test-keystore", type=Path)
    parser.add_argument(
        "--boundary", action="store_true", help="打包明确记录补丁的 P00-04 实验变体"
    )
    args = parser.parse_args()
    if args.test_keystore:
        existing = args.test_keystore.resolve()
        if (
            existing.parent.parent != Path("/tmp")
            or not existing.parent.name.startswith("chuanmen-p00-")
            or existing.name != "experiment-only.p12"
        ):
            parser.error("仅允许本实验此前创建的临时签名，禁止访问生产签名")
    out = args.out.resolve()
    if out.exists() and any(out.iterdir()):
        parser.error("输出目录必须为空")
    native = json.loads(args.native_build_report.read_text())
    if native.get("status") != "passed" or native.get("candidate_commit") != CORE:
        parser.error("要求固定候选的成功原生构建报告")
    if bool(native.get("patches")) != args.boundary:
        parser.error("上游无补丁解析实验与自有补丁边界实验必须明确区分")
    libs = native["libraries"]
    if not args.boundary and (
        {x["abi"] for x in libs} != {"arm64-v8a", "x86_64"} or len(libs) != 2
    ):
        parser.error("要求两种已核验 ABI")
    for lib in libs:
        if sha(Path(lib["path"])) != lib["sha256"]:
            parser.error("上游库哈希不匹配")
    out.mkdir(parents=True, exist_ok=True)
    tools = args.sdk.resolve() / "build-tools/36.0.0"
    platform = args.sdk.resolve() / "platforms/android-37.0"
    android = platform / "android.jar"
    source = ROOT / ("vpn-runtime/android" if args.boundary else "jni-parser/android")
    sources = sorted((source / "src").rglob("*.java"))
    assets = probe_assets(args.boundary)
    report = {
        "schema_version": 1,
        "layer": "android_native_boundary"
        if args.boundary
        else "android_native_jni_parser",
        "status": "failed",
        "core_commit": CORE,
        "recorded_at_utc": datetime.now(UTC).isoformat(),
        "network_started": False,
        "patches": native.get("patches", []),
        "libraries": libs,
        "native_build_report_sha256": sha(args.native_build_report),
        "source_sha256": {},
        "tool_sha256": {},
        "commands": [],
        "min_sdk": 24,
        "target_sdk": 37,
        "compile_sdk": "37.0",
        "build_tools": "36.0.0",
    }
    env = os.environ.copy()
    env["TMPDIR"] = str(out)

    def run(cmd, recorded=None):
        result = subprocess.run(
            [str(x) for x in cmd],
            check=False,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            timeout=120,
        )
        report["commands"].append(
            {
                "argv": recorded or [str(x) for x in cmd],
                "exit_code": result.returncode,
                "output": result.stdout,
            }
        )
        if result.returncode:
            raise RuntimeError(f"构建失败：{cmd[0]}")
        return result.stdout

    try:
        for path in [
            Path(__file__).resolve(),
            source / "AndroidManifest.xml",
            *sources,
            *assets.values(),
        ]:
            report["source_sha256"][str(path.relative_to(ROOT))] = sha(path)
        for name in ("aapt2", "d8", "zipalign", "apksigner"):
            report["tool_sha256"][name] = sha(tools / name)
        for name in ("d8.jar", "apksigner.jar"):
            report["tool_sha256"][name] = sha(tools / "lib" / name)
        report["android_jar_sha256"] = sha(android)
        report["platform_source_properties"] = (
            platform / "source.properties"
        ).read_text()
        report["build_tools_source_properties"] = (
            tools / "source.properties"
        ).read_text()
        report["jdk"] = run(["java", "-version"])
        for name in ("java", "javac", "keytool"):
            report["tool_sha256"][name] = sha(Path(shutil.which(name)))
        classes, dex = out / "classes", out / "dex"
        classes.mkdir()
        dex.mkdir()
        run(
            [
                "javac",
                "-encoding",
                "UTF-8",
                "-source",
                "8",
                "-target",
                "8",
                "-bootclasspath",
                android,
                "-d",
                classes,
                *sources,
            ]
        )
        run(
            [
                tools / "d8",
                "--lib",
                android,
                "--min-api",
                "24",
                "--output",
                dex,
                *sorted(classes.rglob("*.class")),
            ]
        )
        unsigned = out / "unsigned.apk"
        run(
            [
                tools / "aapt2",
                "link",
                "-I",
                android,
                "--manifest",
                source / "AndroidManifest.xml",
                "--min-sdk-version",
                "24",
                "--target-sdk-version",
                "37",
                "-o",
                unsigned,
            ]
        )
        with zipfile.ZipFile(unsigned, "a") as archive:
            write_assets(archive, assets)
            for path in sorted(dex.glob("*.dex")):
                entry = zipfile.ZipInfo(path.name, (2026, 1, 1, 0, 0, 0))
                entry.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(entry, path.read_bytes())
            for lib in libs:
                entry = zipfile.ZipInfo(
                    "lib/" + lib["abi"] + "/libeasytier_android_jni.so",
                    (2026, 1, 1, 0, 0, 0),
                )
                entry.compress_type = zipfile.ZIP_STORED
                archive.writestr(entry, Path(lib["path"]).read_bytes())
        aligned = out / "aligned.apk"
        run([tools / "zipalign", "-f", "-P", "16", "4", unsigned, aligned])
        key = (
            args.test_keystore.resolve()
            if args.test_keystore
            else out / "experiment-only.p12"
        )
        if not args.test_keystore:
            run(
                [
                    "keytool",
                    "-genkeypair",
                    "-keystore",
                    key,
                    "-storetype",
                    "PKCS12",
                    "-storepass",
                    "p00-test-only",
                    "-alias",
                    "p00",
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-validity",
                    "30",
                    "-dname",
                    "CN=Chuanmen P00 experiment",
                ],
                ["keytool", "-genkeypair", "synthetic-test-signing"],
            )
        report["temporary_test_signature_reused"] = bool(args.test_keystore)
        apk = out / (
            "p00-native-boundary.apk" if args.boundary else "p00-native-parser.apk"
        )
        run(
            [
                tools / "apksigner",
                "sign",
                "--ks",
                key,
                "--ks-pass",
                "pass:p00-test-only",
                "--out",
                apk,
                aligned,
            ],
            ["apksigner", "sign", "experiment-only.p12", str(apk)],
        )
        report["signature_verification"] = run(
            [tools / "apksigner", "verify", "--verbose", "--print-certs", apk]
        )
        report["apk_alignment"] = run(
            [tools / "zipalign", "-c", "-P", "16", "-v", "4", apk]
        )
        report.update(
            status="passed",
            apk_path=str(apk),
            apk_sha256=sha(apk),
            apk_bytes=apk.stat().st_size,
        )
    except (OSError, RuntimeError, subprocess.TimeoutExpired) as error:
        report["error"] = str(error)
    (out / "build-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )
    print(
        json.dumps(
            {"status": report["status"], "report": str(out / "build-report.json")},
            ensure_ascii=False,
        )
    )
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
