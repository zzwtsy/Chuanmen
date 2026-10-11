"""从干净固定 checkout 离线构建开发主机实验对端；不会启动进程或修改路由。"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
from datetime import UTC, datetime
from pathlib import Path

CORE = "7592951b808c4839cb9d77cc4bc642f124915951"
LOCK = "cd5a7951f897e45f1988b0a4896ab1f336e03e4cb6d70ca1db2a7b9b477d774b"
RUST = "b940084d7eb6a299eb4bfeb8e34901bc051e7ac4"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--rust-toolchain",
        default="stable",
        help="可选精确 rustup 工具链；无论别名均须通过冻结 compiler SHA 检查",
    )
    parser.add_argument("--core-checkout", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument(
        "--fetch-locked-dependencies",
        action="store_true",
        help="构建前仅获取固定 lockfile 的宿主依赖；实际编译仍离线",
    )
    parser.add_argument(
        "--cache-from",
        type=Path,
        help="可选：复制此前本任务的编译缓存；不会改动原缓存或产物",
    )
    parser.add_argument(
        "--gateway",
        action="store_true",
        help="P00-07 额外启用真实 gateway 所需的 socks5 编译特性",
    )
    args = parser.parse_args()
    core, out = args.core_checkout.resolve(), args.out.resolve()
    if out.exists() and any(out.iterdir()):
        parser.error("输出必须为空")
    out.mkdir(parents=True, exist_ok=True)
    if args.cache_from:
        cache = args.cache_from.resolve()
        cache_root = (
            Path(__file__).resolve().parents[4] / "build/experiments/android-vpn-smoke"
        )
        legacy_cache = cache.parent.parent == Path(
            "/tmp"
        ) and cache.parent.name.startswith("chuanmen-p00-host-")
        if cache.name != "target" or not (
            cache.is_relative_to(cache_root) or legacy_cache
        ):
            parser.error("仅允许本任务的宿主编译缓存")
        shutil.copytree(cache, out / "target")
    env = os.environ.copy()
    (out / "tmp").mkdir()
    env.update(
        CARGO_TARGET_DIR=str(out / "target"),
        CARGO_BUILD_JOBS="2",
        CARGO_INCREMENTAL="0",
        RUSTFLAGS="-C link-arg=-fuse-ld=bfd",
        TMPDIR=str(out / "tmp"),
    )
    report = {
        "schema_version": 1,
        "layer": "host_peer_build",
        "status": "failed",
        "core_commit": CORE,
        "recorded_at_utc": datetime.now(UTC).isoformat(),
        "patches": [],
        # 固定上游 runtime_core_host_config 仅在 kcp/quic 编译特性存在时启用包代理。
        "features": "management,smoltcp,tun,aes-gcm,quic"
        + (",socks5" if args.gateway else ""),
        "default_features": False,
        "profile": "release",
        "runner_sha256": sha(Path(__file__)),
        "network_started": False,
        "commands": [],
        "fetch_locked_dependencies": args.fetch_locked_dependencies,
        "copied_cache_from": str(args.cache_from) if args.cache_from else None,
        "environment_overrides": {
            k: env[k]
            for k in (
                "CARGO_TARGET_DIR",
                "CARGO_BUILD_JOBS",
                "CARGO_INCREMENTAL",
                "RUSTFLAGS",
            )
        },
    }

    def run(argv):
        path = out / f"command-{len(report['commands']) + 1:02}.txt"
        with path.open("x") as stream:
            result = subprocess.run(
                argv,
                check=False,
                cwd=core,
                env=env,
                text=True,
                stdout=stream,
                stderr=subprocess.STDOUT,
            )
        report["commands"].append(
            {
                "argv": argv,
                "exit_code": result.returncode,
                "log_path": str(path),
                "log_sha256": sha(path),
            }
        )
        if result.returncode:
            raise RuntimeError(f"命令失败：{argv[0]}")
        return path.read_text()

    try:
        if (
            run(["git", "rev-parse", "HEAD"]).strip() != CORE
            or run(["git", "status", "--porcelain"]).strip()
        ):
            raise RuntimeError("核心 checkout 不是干净固定版本")
        report["cargo_lock_sha256"] = sha(core / "Cargo.lock")
        if report["cargo_lock_sha256"] != LOCK:
            raise RuntimeError("核心 lockfile 不匹配")
        report["rustc"] = run(["rustup", "run", args.rust_toolchain, "rustc", "-Vv"])
        if f"commit-hash: {RUST}" not in report["rustc"]:
            raise RuntimeError("编译器 SHA 不匹配")
        report["cargo"] = run(["rustup", "run", args.rust_toolchain, "cargo", "-V"])
        report["linker"] = run(["ld.bfd", "--version"])
        if args.fetch_locked_dependencies:
            run(
                [
                    "rustup",
                    "run",
                    args.rust_toolchain,
                    "cargo",
                    "fetch",
                    "--locked",
                    "--target",
                    "x86_64-unknown-linux-gnu",
                ]
            )
        run(
            [
                "rustup",
                "run",
                args.rust_toolchain,
                "cargo",
                "build",
                "--locked",
                "--offline",
                "--release",
                "-p",
                "easytier",
                "--bin",
                "easytier-core",
                "--no-default-features",
                "--features",
                report["features"],
            ]
        )
        binary = out / "target/release/easytier-core"
        report.update(
            binary_path=str(binary),
            binary_sha256=sha(binary),
            binary_bytes=binary.stat().st_size,
        )
        report["version"] = run([str(binary), "--version"])
        if (
            sha(core / "Cargo.lock") != LOCK
            or run(["git", "status", "--porcelain"]).strip()
        ):
            raise RuntimeError("构建修改了核心 checkout")
        report["status"] = "passed"
    except (OSError, RuntimeError) as error:
        report["error"] = str(error)
    (out / "build-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    )
    print(
        json.dumps(
            {"status": report["status"], "report": str(out / "build-report.json")}
        )
    )
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
