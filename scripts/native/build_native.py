"""构建正式 Rust 桥；固定上游来源，动态计算构建输入，不依赖历史阶段工具。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TARGETS = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}
OVERLAYS = {
    "app/native/host/android_tun.rs": "easytier/src/android_tun.rs",
    "app/native/host/android_ffi.rs": "easytier-contrib/easytier-ffi/src/android_host.rs",
}


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def capture(argv: list[str], cwd: Path = ROOT) -> str:
    return subprocess.check_output(argv, cwd=cwd, text=True, stderr=subprocess.STDOUT)


def inputs(root: Path | None = None) -> dict:
    """读取唯一来源配置；自有源码由 Git 和构建任务管理，不维护手工摘要清单。"""
    root = root or ROOT
    record = json.loads((root / "app/native/inputs.json").read_text())
    if (
        record["schema_version"] != 1
        or record["protocol"] != 1
        or not re.fullmatch(r"[0-9a-f]{40}", record["core_commit"])
        or set(record["abis"]) != set(TARGETS)
        or record["api"] != 24
    ):
        raise ValueError("正式 native 来源配置无效")
    return record


def input_fingerprint(root: Path | None = None) -> str:
    """动态绑定实际构建输入；只修改测试或说明不会使动态库失效。"""
    root = root or ROOT
    native = root / "app/native"
    files = {native / name for name in ("Cargo.toml", "Cargo.lock", "inputs.json")}
    for directory in ("src", "host", "patches"):
        files.update(path for path in (native / directory).rglob("*") if path.is_file())
    files.add(root / "scripts/native/build_native.py")
    fingerprints = {
        path.relative_to(root).as_posix(): digest(path) for path in sorted(files)
    }
    return hashlib.sha256(json.dumps(fingerprints, sort_keys=True).encode()).hexdigest()


def verify_checkout(checkout: Path, record: dict) -> None:
    if capture(["git", "rev-parse", "HEAD"], checkout).strip() != record["core_commit"]:
        raise ValueError("上游 checkout 不是固定 commit")
    changed = {
        line[3:]
        for line in capture(
            ["git", "status", "--porcelain", "--untracked-files=all"], checkout
        ).splitlines()
    }
    if changed != set(record["upstream_files"]) | set(OVERLAYS.values()):
        raise ValueError("上游改动集合与正式补丁身份不匹配")
    for name, hashes in record["upstream_files"].items():
        if digest(checkout / name) != hashes["patched_sha256"]:
            raise ValueError(f"上游正式补丁哈希不匹配：{name}")
        if hashes["original_sha256"] is not None:
            original = subprocess.check_output(
                ["git", "show", f"{record['core_commit']}:{name}"], cwd=checkout
            )
            if hashlib.sha256(original).hexdigest() != hashes["original_sha256"]:
                raise ValueError("上游原始文件身份不匹配")
    for source, destination in OVERLAYS.items():
        if digest(checkout / destination) != digest(ROOT / source):
            raise ValueError("上游 Android 适配与当前正式源码不匹配")
    if digest(checkout / "Cargo.lock") != record["upstream_lock_sha256"]:
        raise ValueError("固定上游依赖锁发生变化")


def prepare(record: dict, allow_download: bool) -> Path:
    checkout = ROOT / "app/build/native/upstream"
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
            ["git", "fetch", "--depth=1", "origin", record["core_commit"]],
            cwd=checkout,
            check=True,
        )
        subprocess.run(
            ["git", "checkout", "--detach", "FETCH_HEAD"], cwd=checkout, check=True
        )
    if not capture(["git", "status", "--porcelain"], checkout).strip():
        if (
            capture(["git", "rev-parse", "HEAD"], checkout).strip()
            != record["core_commit"]
        ):
            raise ValueError("拒绝对其他来源应用正式补丁")
        patch = ROOT / "app/native/patches/session-host.diff"
        subprocess.run(
            ["git", "apply", "--check", str(patch)], cwd=checkout, check=True
        )
        subprocess.run(["git", "apply", str(patch)], cwd=checkout, check=True)
    for source, destination in OVERLAYS.items():
        overlay = checkout / destination
        # 相同适配内容不重写，避免单纯准备对端或重跑校验使 Cargo 的输入时间戳失效。
        if not overlay.is_file() or digest(overlay) != digest(ROOT / source):
            shutil.copyfile(ROOT / source, overlay)
    verify_checkout(checkout, record)
    # 缓存的固定后像还必须对应当前补丁，不能只以旧来源哈希接受已变化的补丁文件。
    subprocess.run(
        [
            "git",
            "apply",
            "--reverse",
            "--check",
            str(ROOT / "app/native/patches/session-host.diff"),
        ],
        cwd=checkout,
        check=True,
    )
    return checkout


def load_segments_aligned(headers: str) -> bool:
    """每个 LOAD 段都至少 16 KB；没有 LOAD 段不能作为成功。"""
    segments = [
        line.split()
        for line in headers.splitlines()
        if line.strip().startswith("LOAD ")
    ]
    return bool(segments) and all(int(segment[-1], 16) >= 16384 for segment in segments)


def has_jni_entry(symbols: str) -> bool:
    """匹配实际导出的完整符号，旧基础包入口或相似前缀均不能通过。"""
    return (
        "Java_com_github_zzwtsy_chuanmen_lib_vpn_NativeBindings_request"
        in symbols.split()
    )


def run_build(args: argparse.Namespace, record: dict) -> None:
    if f"commit-hash: {record['rustc_commit']}" not in capture(
        ["rustc", f"+{record['rust_version']}", "-Vv"]
    ):
        raise ValueError("Rust 编译器不是固定 1.99.0 SHA")
    if (
        capture(["cargo", f"+{record['rust_version']}", "ndk", "--version"]).strip()
        != f"cargo-ndk {record['cargo_ndk_version']}"
    ):
        raise ValueError("cargo-ndk 版本不匹配")
    ndk = args.sdk.resolve() / "ndk" / record["ndk_revision"]
    if (
        f"Pkg.Revision = {record['ndk_revision']}"
        not in (ndk / "source.properties").read_text()
    ):
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
            "CARGO_TARGET_DIR": str(ROOT / "app/build/native/target"),
            "CARGO_BUILD_JOBS": "2",
            "CARGO_INCREMENTAL": "0",
            "RUSTFLAGS": f"--remap-path-prefix={ROOT}=workspace --remap-path-prefix={Path.home()}=user -C link-arg=-Wl,-z,max-page-size=16384 -C link-arg=-Wl,-z,common-page-size=16384",
        }
    )
    output = ROOT / "app/build/native"
    report = {
        "schema_version": 1,
        "status": "building",
        "source_sha256": input_fingerprint(),
        "protocol": 1,
        "core_commit": record["core_commit"],
        "device_status": "not_executed",
        "libraries": [],
    }
    report_path = output / "build-report.json"
    try:
        for abi in args.abi or list(TARGETS):
            target = TARGETS[abi]
            env[f"BINDGEN_EXTRA_CLANG_ARGS_{target}"] = (
                f"--target={target}{record['api']} --sysroot={llvm / 'sysroot'}"
            )
            argv = [
                "cargo",
                f"+{record['rust_version']}",
                "ndk",
                "-t",
                abi,
                "-P",
                str(record["api"]),
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
                    cwd=ROOT / "app/native",
                    env=env,
                    stdout=log,
                    stderr=subprocess.STDOUT,
                    check=True,
                    timeout=1800,
                )
            library = (
                ROOT / f"app/build/native/target/{target}/release/libchuanmen_core.so"
            )
            headers = capture([str(llvm / "bin/llvm-readelf"), "-lW", str(library)])
            if not load_segments_aligned(headers):
                raise ValueError(f"{abi} ELF LOAD 段未满足 16 KB 对齐")
            symbols = capture(
                [str(llvm / "bin/llvm-readelf"), "--dyn-syms", "--wide", str(library)]
            )
            if not has_jni_entry(symbols):
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
        if input_fingerprint() != report["source_sha256"]:
            raise ValueError("原生构建期间源码变化，不能发布本次产物")
        report["status"] = "built"
    except (OSError, ValueError, subprocess.SubprocessError):
        report["status"] = "failed"
        raise
    finally:
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


def verify_artifacts() -> None:
    """只接受本次正式输入生成的双 ABI 产物，不把旧产物或未运行当作成功。"""
    report = json.loads((ROOT / "app/build/native/build-report.json").read_text())
    if report["status"] != "built" or report["source_sha256"] != input_fingerprint():
        raise ValueError("正式 native 构建报告缺失或身份已失效")
    if {item["abi"] for item in report["libraries"]} != set(TARGETS):
        raise ValueError("正式 native 产物必须覆盖双 ABI")
    for item in report["libraries"]:
        expected_path = f"app/build/native/jni/{item['abi']}/libchuanmen_core.so"
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
            verify_checkout(ROOT / "app/build/native/upstream", record)
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
