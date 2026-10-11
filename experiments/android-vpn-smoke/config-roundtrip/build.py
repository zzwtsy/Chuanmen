"""构建固定核心真实 Rust 配置读回探针；上游保持干净，依赖必须是原 lockfile 的子集。"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import tomllib
from datetime import UTC, datetime
from pathlib import Path

CORE = "7592951b808c4839cb9d77cc4bc642f124915951"
LOCK = "cd5a7951f897e45f1988b0a4896ab1f336e03e4cb6d70ca1db2a7b9b477d774b"


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
        "--cache-from", type=Path, help="复制此前本探针的缓存，不修改已有产物"
    )
    args = parser.parse_args()
    core, out = args.core_checkout.resolve(), args.out.resolve()
    if out.exists() and any(out.iterdir()):
        parser.error("输出目录必须为空")
    out.mkdir(parents=True, exist_ok=True)
    if args.cache_from:
        cache = args.cache_from.resolve()
        if cache.name != "target" or not cache.is_relative_to(
            Path(__file__).resolve().parents[3] / "build/experiments/android-vpn-smoke"
        ):
            parser.error("仅允许 build/experiments/android-vpn-smoke 内的构建缓存")
        shutil.copytree(cache, out / "target")
    report = {
        "task": "P00-03",
        "layer": "host_config_probe_build",
        "status": "failed",
        "recorded_at_utc": datetime.now(UTC).isoformat(),
        "commit": CORE,
        "network_started": False,
        "commands": [],
        "runner_sha256": sha(Path(__file__)),
    }
    env = os.environ.copy()
    (out / "tmp").mkdir()
    env.update(
        TMPDIR=str(out / "tmp"),
        CARGO_TARGET_DIR=str(out / "target"),
        CARGO_BUILD_JOBS="2",
        CARGO_INCREMENTAL="0",
        RUSTFLAGS="-C link-arg=-fuse-ld=bfd",
    )

    def run(argv, cwd=core):
        result = subprocess.run(
            argv,
            check=False,
            cwd=cwd,
            env=env,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )
        name = out / f"command-{len(report['commands']) + 1:02d}.txt"
        name.write_text(result.stdout)
        report["commands"].append(
            {
                "argv": argv,
                "exit_code": result.returncode,
                "log_path": str(name),
                "log_sha256": sha(name),
            }
        )
        if result.returncode:
            raise RuntimeError("构建步骤失败：" + argv[0])
        return result.stdout

    try:
        if (
            run(["git", "rev-parse", "HEAD"]).strip() != CORE
            or run(["git", "status", "--porcelain"]).strip()
        ):
            raise RuntimeError("上游不是干净固定版本")
        if sha(core / "Cargo.lock") != LOCK:
            raise RuntimeError("上游 lockfile 哈希不符")
        report["rustc"] = run(["rustup", "run", args.rust_toolchain, "rustc", "-Vv"])
        if (
            "commit-hash: b940084d7eb6a299eb4bfeb8e34901bc051e7ac4"
            not in report["rustc"]
        ):
            raise RuntimeError("Rust 编译器 SHA 不符")
        original = tomllib.loads((core / "Cargo.lock").read_text())["package"]
        serde_version = next(
            p["version"] for p in original if p["name"] == "serde_json"
        )
        probe = out / "probe"
        (probe / "src").mkdir(parents=True)
        source = Path(__file__).resolve().with_name("main.rs")
        shutil.copyfile(source, probe / "src/main.rs")
        report["probe_source_sha256"] = sha(source)
        (probe / "Cargo.toml").write_text(
            '[package]\nname="p00-config-roundtrip"\nversion="0.0.1"\nedition="2024"\n[workspace]\n[dependencies]\n'
            + "easytier-core={path="
            + json.dumps(str(core / "easytier-core"))
            + ',default-features=false,features=["config-write"]}\n'
            + 'serde_json="='
            + serde_version
            + '"\n'
        )
        shutil.copyfile(core / "Cargo.lock", probe / "Cargo.lock")
        # 只更新实验 workspace 根包，继续沿用已有依赖版本；重新生成会选择其他版本。
        run(
            [
                "rustup",
                "run",
                args.rust_toolchain,
                "cargo",
                "update",
                "--workspace",
                "--offline",
            ],
            probe,
        )
        derived = tomllib.loads((probe / "Cargo.lock").read_text())["package"]

        def identity(p):
            return (p["name"], p["version"], p.get("source"), p.get("checksum"))

        original_ids = {identity(p) for p in original}
        unexpected = [
            p
            for p in derived
            if p["name"] != "p00-config-roundtrip" and identity(p) not in original_ids
        ]
        if unexpected:
            raise RuntimeError("派生 lockfile 引入未冻结依赖：" + repr(unexpected))
        report.update(
            upstream_lock_sha256=LOCK,
            derived_lock_sha256=sha(probe / "Cargo.lock"),
            dependency_identity_subset_verified=True,
            derived_packages=len(derived),
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
            ],
            probe,
        )
        binary = out / "target/release/p00-config-roundtrip"
        if (
            run(["git", "status", "--porcelain"]).strip()
            or sha(core / "Cargo.lock") != LOCK
        ):
            raise RuntimeError("构建修改了上游")
        report.update(
            status="passed",
            binary_path=str(binary),
            binary_sha256=sha(binary),
            binary_bytes=binary.stat().st_size,
        )
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
    return 0 if report["status"] == "passed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
