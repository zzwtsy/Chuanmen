"""编译固定上游 JNI 库并记录真实结果；编译通过不代表 Android 数据通路通过。"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from datetime import UTC, datetime
from pathlib import Path

COMMIT = "7592951b808c4839cb9d77cc4bc642f124915951"
RUSTC_COMMIT = "b940084d7eb6a299eb4bfeb8e34901bc051e7ac4"
NDK_REVISION = "30.0.16248370"
TARGETS = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--rust-toolchain",
        default="stable",
        help="可选精确 rustup 工具链；无论别名均须通过冻结 compiler SHA 检查",
    )
    parser.add_argument("--checkout", type=Path, required=True)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--abi", choices=TARGETS, action="append", required=True)
    parser.add_argument("--allow-download", action="store_true")
    parser.add_argument(
        "--cache-from",
        type=Path,
        help="复制 /tmp/chuanmen-p00-*/target 缓存到空输出目录，保留源缓存",
    )
    parser.add_argument(
        "--experimental-patch-report",
        type=Path,
        help="仅接受独立 checkout 中逐文件记录的 P00 自有观察补丁",
    )
    parser.add_argument(
        "--bindgen-api-target",
        action="store_true",
        help="为 bindgen 显式设置 Android API 24 target，与 native 编译的 API 级别保持一致",
    )
    args = parser.parse_args()
    if args.out.exists() and any(args.out.iterdir()):
        parser.error("输出目录必须为空，禁止覆盖失败记录")
    args.out.mkdir(parents=True, exist_ok=True)
    if args.cache_from:
        cache = args.cache_from.resolve()
        if (
            cache.name != "target"
            or cache.parent.parent != Path("/tmp")
            or not cache.parent.name.startswith("chuanmen-p00-")
        ):
            parser.error("缓存路径必须为 /tmp/chuanmen-p00-*/target")
        shutil.copytree(cache, args.out / "target")
    checkout = args.checkout.resolve()
    ndk = args.sdk.resolve() / "ndk" / NDK_REVISION
    report = {
        "schema_version": 1,
        "layer": "android_native_build",
        "status": "blocked",
        "device_status": "unverified",
        "candidate_commit": COMMIT,
        "recorded_at_utc": datetime.now(UTC).isoformat(),
        "patches": [],
        "copied_cache_from": str(args.cache_from) if args.cache_from else None,
        "ndk_revision": NDK_REVISION,
        "api": 24,
        "package": "easytier-android-jni",
        "features": "upstream default features",
        "profile": "release",
        "abis": list(dict.fromkeys(args.abi)),
        "runner_sha256": digest(Path(__file__)),
        "bindgen_api_target": args.bindgen_api_target,
        "commands": [],
        "libraries": [],
    }
    env = os.environ.copy()
    overrides = {
        "ANDROID_NDK_HOME": str(ndk),
        "ANDROID_NDK_ROOT": str(ndk),
        "CARGO_TARGET_DIR": str(args.out.resolve() / "target"),
        "CARGO_BUILD_JOBS": "2",
        "CARGO_INCREMENTAL": "0",
    }
    env.update(overrides)
    report["environment_overrides"] = overrides

    def verify_checkout(status):
        if not args.experimental_patch_report:
            if status.strip():
                raise RuntimeError("上游 checkout 有未记录改动")
            return
        patch = json.loads(args.experimental_patch_report.read_text())
        if (
            patch.get("core_commit") != COMMIT
            or patch.get("layer") != "experimental_project_patch"
            or patch.get("production_approved") is not False
            or Path(patch["checkout"]).resolve() != checkout
        ):
            raise RuntimeError("实验补丁归属不匹配")
        changed = {line[3:] for line in status.splitlines()}
        if changed != set(patch["files"]):
            raise RuntimeError("checkout 改动与逐文件补丁清单不一致")
        for name, hashes in patch["files"].items():
            path = (checkout / name).resolve()
            if (
                not path.is_relative_to(checkout)
                or digest(path) != hashes["patched_sha256"]
            ):
                raise RuntimeError("实验补丁源码哈希不匹配")
            if hashes["original_sha256"]:
                original = subprocess.check_output(
                    ["git", "show", COMMIT + ":" + name], cwd=checkout
                )
                if hashlib.sha256(original).hexdigest() != hashes["original_sha256"]:
                    raise RuntimeError("实验补丁基线哈希不匹配")
        if digest(checkout / "Cargo.lock") != patch["lockfile_sha256"]:
            raise RuntimeError("实验补丁改动了 lockfile")
        report["patches"] = [
            {
                "report_path": str(args.experimental_patch_report),
                "report_sha256": digest(args.experimental_patch_report),
                "files": patch["files"],
                "production_approved": False,
            }
        ]

    def run(command, timeout=120):
        path = args.out / f"command-{len(report['commands']) + 1:02}.txt"
        entry = {"argv": [str(x) for x in command], "log_path": str(path)}
        report["commands"].append(entry)
        with path.open("x") as log:
            try:
                result = subprocess.run(
                    entry["argv"],
                    check=False,
                    cwd=checkout,
                    env=env,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    timeout=timeout,
                )
                entry["exit_code"] = result.returncode
            except subprocess.TimeoutExpired:
                entry["timed_out"] = True
                raise
            finally:
                log.flush()
                entry["log_sha256"] = digest(path)
        text = path.read_text(errors="replace")
        if result.returncode:
            entry["output"] = text
            raise RuntimeError(f"命令失败，原始日志 {path}，退出码 {result.returncode}")
        return text

    try:
        if any(env.get(key) for key in ("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS")):
            raise RuntimeError("存在未冻结的 Rust 编译参数，停止而不覆盖用户配置")
        if not (checkout / "Cargo.lock").is_file():
            raise FileNotFoundError("缺少完整上游工作区与 Cargo.lock")
        if run(["git", "rev-parse", "HEAD"]).strip() != COMMIT:
            raise RuntimeError("核心完整 SHA 不匹配")
        verify_checkout(run(["git", "status", "--porcelain", "--untracked-files=all"]))
        report["cargo_lock_sha256"] = digest(checkout / "Cargo.lock")
        versions = {
            "rustc": run(["rustup", "run", args.rust_toolchain, "rustc", "-Vv"]),
            "cargo": run(["rustup", "run", args.rust_toolchain, "cargo", "-V"]),
            "cargo_ndk": run(
                ["rustup", "run", args.rust_toolchain, "cargo", "ndk", "--version"]
            ),
        }
        report["tool_versions"] = versions
        if f"commit-hash: {RUSTC_COMMIT}" not in versions["rustc"]:
            raise RuntimeError("stable 编译器与冻结版本不匹配")
        if versions["cargo_ndk"].strip() != "cargo-ndk 4.1.2":
            raise RuntimeError("cargo-ndk 版本不匹配")
        properties = ndk / "source.properties"
        report["ndk_source_properties"] = properties.read_text()
        report["ndk_source_properties_sha256"] = digest(properties)
        if f"Pkg.Revision = {NDK_REVISION}" not in report["ndk_source_properties"]:
            raise RuntimeError("NDK 版本不匹配")
        llvm = ndk / "toolchains/llvm/prebuilt/linux-x86_64/bin"
        report["ndk_tool_sha256"] = {
            name: digest(llvm / name) for name in ("clang", "ld.lld", "llvm-readelf")
        }
        report["clang_version"] = run([llvm / "clang", "--version"])
        report["status"] = "failed"
        for abi in report["abis"]:
            if args.bindgen_api_target:
                key = "BINDGEN_EXTRA_CLANG_ARGS_" + TARGETS[abi]
                if env.get(key):
                    raise RuntimeError("存在未冻结的 bindgen 参数，不覆盖用户配置")
                # API 后缀必须与 cargo-ndk -P 24 一致；仅修正绑定生成环境，不改上游源码。
                value = f"--target={TARGETS[abi]}24 --sysroot={ndk}/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
                env[key] = value
                report["environment_overrides"][key] = value
            command = [
                "rustup",
                "run",
                args.rust_toolchain,
                "cargo",
                "ndk",
                "-t",
                abi,
                "-P",
                "24",
                "build",
                "--locked",
                "--release",
                "-p",
                "easytier-android-jni",
            ]
            if not args.allow_download:
                command.append("--offline")
            run(command, timeout=1800)
            library = (
                args.out.resolve()
                / "target"
                / TARGETS[abi]
                / "release/libeasytier_android_jni.so"
            )
            item = {
                "abi": abi,
                "path": str(library),
                "sha256": digest(library),
                "size_bytes": library.stat().st_size,
            }
            item["elf"] = run(
                [llvm / "llvm-readelf", "-h", "-d", "--dyn-syms", library]
            )
            report["libraries"].append(item)
        if digest(checkout / "Cargo.lock") != report["cargo_lock_sha256"]:
            raise RuntimeError("构建后 lockfile 变化")
        verify_checkout(run(["git", "status", "--porcelain", "--untracked-files=all"]))
        report["status"] = "passed"
    except (OSError, ValueError, RuntimeError, subprocess.TimeoutExpired) as error:
        report["error"] = str(error)
    output = args.out / "native-build-report.json"
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    print(
        json.dumps(
            {
                "status": report["status"],
                "device_status": "unverified",
                "libraries": len(report["libraries"]),
                "report": str(output),
            },
            ensure_ascii=False,
        )
    )
    return 0 if report["status"] == "passed" else 2


if __name__ == "__main__":
    sys.exit(main())
