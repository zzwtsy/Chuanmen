"""构建 P02 正式桥；核对固定来源和逐文件输入，不读取 P00 产物批准状态。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
COMMIT = "7592951b808c4839cb9d77cc4bc642f124915951"
RUSTC_COMMIT = "b940084d7eb6a299eb4bfeb8e34901bc051e7ac4"
NDK = "30.0.16248370"
TARGETS = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}
OVERLAYS = {
    "native/host/android_tun.rs": "easytier/src/android_tun.rs",
    "native/host/android_ffi.rs": "easytier-contrib/easytier-ffi/src/android_host.rs",
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def capture(argv: list[str], cwd: Path = ROOT) -> str:
    return subprocess.check_output(argv, cwd=cwd, text=True, stderr=subprocess.STDOUT)


def inputs(root: Path = ROOT) -> dict:
    """构建前核对完整输入集合，新增或删除原生源码同样使身份失效。"""
    path = root / "native/inputs.json"
    record = json.loads(path.read_text())
    if record["core_commit"] != COMMIT or record["protocol"] != 1:
        raise ValueError("正式 native 输入基线不匹配")
    expected = {
        str(path.relative_to(root))
        for pattern in ("src/*.rs", "host/*.rs", "tests/*.rs", "patches/*.diff")
        for path in (root / "native").glob(pattern)
    } | {"native/Cargo.toml", "native/Cargo.lock", "scripts/native/build_native.py"}
    if set(record["files"]) != expected:
        raise ValueError("正式 native 输入集合发生变化")
    for name, sha256 in record["files"].items():
        if digest(root / name) != sha256:
            raise ValueError(f"正式 native 输入哈希不匹配：{name}")
    return record


def verify_checkout(checkout: Path, record: dict) -> None:
    if capture(["git", "rev-parse", "HEAD"], checkout).strip() != COMMIT:
        raise ValueError("上游 checkout 不是固定 commit")
    changed = {
        line[3:]
        for line in capture(
            ["git", "status", "--porcelain", "--untracked-files=all"], checkout
        ).splitlines()
    }
    if changed != set(record["upstream_files"]):
        raise ValueError("上游改动集合与正式补丁身份不匹配")
    for name, hashes in record["upstream_files"].items():
        if digest(checkout / name) != hashes["patched_sha256"]:
            raise ValueError(f"上游正式补丁哈希不匹配：{name}")
        if hashes["original_sha256"] is not None:
            original = subprocess.check_output(
                ["git", "show", f"{COMMIT}:{name}"], cwd=checkout
            )
            if hashlib.sha256(original).hexdigest() != hashes["original_sha256"]:
                raise ValueError("上游原始文件身份不匹配")
    if digest(checkout / "Cargo.lock") != record["upstream_lock_sha256"]:
        raise ValueError("固定上游依赖锁发生变化")


def prepare(record: dict, allow_download: bool) -> Path:
    checkout = ROOT / "build/native/upstream"
    if not (checkout / ".git").is_dir():
        if not allow_download:
            raise ValueError("缺少固定源码；首次准备须显式 --allow-download")
        checkout.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            ["git", "init", str(checkout)], check=True, stdout=subprocess.DEVNULL
        )
        subprocess.run(
            [
                "git",
                "remote",
                "add",
                "origin",
                "https://github.com/EasyTier/EasyTier.git",
            ],
            cwd=checkout,
            check=True,
        )
        subprocess.run(
            ["git", "fetch", "--depth=1", "origin", COMMIT], cwd=checkout, check=True
        )
        subprocess.run(
            ["git", "checkout", "--detach", "FETCH_HEAD"], cwd=checkout, check=True
        )
    if not capture(["git", "status", "--porcelain"], checkout).strip():
        if capture(["git", "rev-parse", "HEAD"], checkout).strip() != COMMIT:
            raise ValueError("拒绝对其他来源应用正式补丁")
        patch = ROOT / "native/patches/session-host.diff"
        subprocess.run(
            ["git", "apply", "--check", str(patch)], cwd=checkout, check=True
        )
        subprocess.run(["git", "apply", str(patch)], cwd=checkout, check=True)
        for source, destination in OVERLAYS.items():
            shutil.copyfile(ROOT / source, checkout / destination)
    verify_checkout(checkout, record)
    return checkout


def load_segments_aligned(headers: str) -> bool:
    """每个 LOAD 段都至少 16 KB；没有 LOAD 段不能作为成功。"""
    segments = [
        line.split()
        for line in headers.splitlines()
        if line.strip().startswith("LOAD ")
    ]
    return bool(segments) and all(int(segment[-1], 16) >= 16384 for segment in segments)


def run_build(args: argparse.Namespace, record: dict) -> None:
    if f"commit-hash: {RUSTC_COMMIT}" not in capture(["rustc", "-Vv"]):
        raise ValueError("Rust 编译器不是固定 1.99.0 SHA")
    if capture(["cargo", "ndk", "--version"]).strip() != "cargo-ndk 4.1.2":
        raise ValueError("cargo-ndk 版本不匹配")
    ndk = args.sdk.resolve() / "ndk" / NDK
    if f"Pkg.Revision = {NDK}" not in (ndk / "source.properties").read_text():
        raise ValueError("NDK 版本不匹配")
    llvm = ndk / "toolchains/llvm/prebuilt/linux-x86_64"
    env = os.environ.copy()
    unsafe = {
        key
        for key in env
        if key.startswith(("CARGO_TARGET_", "BINDGEN_EXTRA_CLANG_ARGS"))
    }
    if unsafe or any(env.get(key) for key in ("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS")):
        raise ValueError("存在未冻结的 native 参数，拒绝覆盖")
    env.update(
        {
            "ANDROID_NDK_HOME": str(ndk),
            "ANDROID_NDK_ROOT": str(ndk),
            "LIBCLANG_PATH": str(llvm / "lib"),
            "CARGO_TARGET_DIR": str(ROOT / "native/target"),
            "CARGO_BUILD_JOBS": "2",
            "CARGO_INCREMENTAL": "0",
            "RUSTFLAGS": f"--remap-path-prefix={ROOT}=workspace --remap-path-prefix={Path.home()}=user -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384",
        }
    )
    output = ROOT / "build/native"
    report = {
        "schema_version": 1,
        "status": "building",
        "inputs_sha256": digest(ROOT / "native/inputs.json"),
        "protocol": 1,
        "core_commit": COMMIT,
        "device_status": "not_executed",
        "libraries": [],
    }
    report_path = output / "build-report.json"
    try:
        for abi in args.abi or list(TARGETS):
            target = TARGETS[abi]
            env[f"BINDGEN_EXTRA_CLANG_ARGS_{target}"] = (
                f"--target={target}24 --sysroot={llvm / 'sysroot'}"
            )
            argv = [
                "cargo",
                "ndk",
                "-t",
                abi,
                "-P",
                "24",
                "build",
                "--manifest-path",
                "Cargo.toml",
                "--locked",
                "--release",
                "--lib",
            ]
            if not args.allow_download:
                argv.append("--offline")
            with (output / f"build-{abi}.log").open("w") as log:
                subprocess.run(
                    argv,
                    cwd=ROOT / "native",
                    env=env,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    check=True,
                    timeout=1800,
                )
            library = ROOT / f"native/target/{target}/release/libchuanmen_core.so"
            headers = capture([str(llvm / "bin/llvm-readelf"), "-lW", str(library)])
            if not load_segments_aligned(headers):
                raise ValueError(f"{abi} ELF LOAD 段未满足 16 KB 对齐")
            symbols = capture(
                [str(llvm / "bin/llvm-readelf"), "--dyn-syms", "--wide", str(library)]
            )
            if (
                "Java_com_github_zzwtsy_chuanmen_bridge_NativeBindings_request"
                not in symbols
            ):
                raise ValueError("产物缺少正式 JNI 入口")
            destination = output / f"jni/{abi}/libchuanmen_core.so"
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(library, destination)
            report["libraries"].append(
                {
                    "abi": abi,
                    "path": str(destination.relative_to(ROOT)),
                    "sha256": digest(destination),
                    "size_bytes": destination.stat().st_size,
                    "elf_16kb_aligned": True,
                }
            )
        report["status"] = "built"
    except (OSError, ValueError, subprocess.SubprocessError):
        report["status"] = "failed"
        raise
    finally:
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


def verify_artifacts() -> None:
    """只接受本次正式输入生成的双 ABI 产物，不把旧产物或未运行当作成功。"""
    report = json.loads((ROOT / "build/native/build-report.json").read_text())
    if report["status"] != "built" or report["inputs_sha256"] != digest(
        ROOT / "native/inputs.json"
    ):
        raise ValueError("正式 native 构建报告缺失或身份已失效")
    if {item["abi"] for item in report["libraries"]} != set(TARGETS):
        raise ValueError("正式 native 产物必须覆盖双 ABI")
    for item in report["libraries"]:
        expected_path = f"build/native/jni/{item['abi']}/libchuanmen_core.so"
        if (
            item["path"] != expected_path
            or digest(ROOT / expected_path) != item["sha256"]
            or not item["elf_16kb_aligned"]
        ):
            raise ValueError("正式 native 产物身份不匹配")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "action",
        choices=("prepare", "check", "check-inputs", "build", "verify-artifacts"),
    )
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--abi", choices=TARGETS, action="append")
    parser.add_argument("--allow-download", action="store_true")
    args = parser.parse_args()
    try:
        record = inputs()
        if args.action in ("prepare", "build"):
            prepare(record, args.allow_download)
        if args.action == "check":
            verify_checkout(ROOT / "build/native/upstream", record)
        if args.action == "build":
            if args.sdk is None:
                raise ValueError("正式 native 构建需要 --sdk")
            run_build(args, record)
        if args.action == "verify-artifacts":
            verify_artifacts()
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f"NATIVE_FAILED: {error}", file=sys.stderr)
        return 1
    print(f"NATIVE_OK: {args.action}；不代表设备验收")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
