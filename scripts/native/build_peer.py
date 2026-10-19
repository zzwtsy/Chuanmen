"""构建 P02 用户态 DNS 受控对端，独立绑定固定正式来源和 fixture 输入。"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import subprocess
from pathlib import Path

import build_native

ROOT = build_native.ROOT
PEER = ROOT / "experiments/p02-vpn-core/native-peer"


def source_files() -> dict[str, str]:
    names = [
        *PEER.glob("src/*.rs"),
        PEER / "Cargo.toml",
        PEER / "Cargo.lock",
        Path(__file__).resolve(),
    ]
    return {
        str(path.relative_to(ROOT)): build_native.digest(path) for path in sorted(names)
    }


def validate_report(
    report: dict, sources: dict, inputs: dict, native_hash: str, binary: Path
) -> None:
    """固定来源、资源范围和实际字节均需对应本次输入；不能用任意 passed 字段放行。"""
    expected = {
        "schema_version": 1,
        "status": "passed",
        "role": "controlled_dns_peer",
        "core_commit": build_native.COMMIT,
        "patch_id": inputs["patch_id"],
        "rustc_commit": build_native.RUSTC_COMMIT,
        "source_files": sources,
        "native_inputs_sha256": native_hash,
        "virtual_dns_port": 53,
        "binary_sha256": build_native.digest(binary),
    }
    if (
        any(report.get(key) != value for key, value in expected.items())
        or report.get("host_tun") is not False
        or report.get("system_routes_modified") is not False
    ):
        raise ValueError("受控对端产物与本次输入或资源范围不匹配")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("build", "check"))
    args = parser.parse_args()
    output = ROOT / "build/native/controlled-peer"
    output.mkdir(parents=True, exist_ok=True)
    try:
        inputs = build_native.inputs()
        build_native.verify_checkout(ROOT / "build/native/upstream", inputs)
        compiler = build_native.capture(["rustc", "+1.99.0", "-Vv"])
        if f"commit-hash: {build_native.RUSTC_COMMIT}" not in compiler:
            raise ValueError("受控对端编译器不匹配")
        sources = source_files()
        native_hash = build_native.digest(ROOT / "native/inputs.json")
        report_path = output / "build-report.json"
        binary = PEER / "target/debug/chuanmen-controlled-peer"
        if args.action == "check":
            report = json.loads(report_path.read_text())
            validate_report(report, sources, inputs, native_hash, binary)
        else:
            env = os.environ.copy()
            if any(
                env.get(key)
                for key in ("RUSTFLAGS", "CARGO_ENCODED_RUSTFLAGS", "CARGO_TARGET_DIR")
            ):
                raise ValueError("受控对端存在未登记的编译参数")
            env.update(CARGO_BUILD_JOBS="2", CARGO_INCREMENTAL="0")
            command = [
                "cargo",
                "+1.99.0",
                "build",
                "--locked",
                "--offline",
                "--manifest-path",
                str(PEER / "Cargo.toml"),
            ]
            with (output / "build.log").open("w") as stream:
                subprocess.run(
                    command,
                    env=env,
                    cwd=ROOT,
                    stdout=stream,
                    stderr=subprocess.STDOUT,
                    check=True,
                )
            if (
                source_files() != sources
                or build_native.digest(ROOT / "native/inputs.json") != native_hash
            ):
                raise ValueError("受控对端构建期间输入变化")
            build_native.verify_checkout(ROOT / "build/native/upstream", inputs)
            report = {
                "schema_version": 1,
                "status": "passed",
                "role": "controlled_dns_peer",
                "core_commit": build_native.COMMIT,
                "patch_id": inputs["patch_id"],
                "native_inputs_sha256": native_hash,
                "source_files": sources,
                "rustc_commit": build_native.RUSTC_COMMIT,
                "host_tun": False,
                "system_routes_modified": False,
                "virtual_dns_port": 53,
                "binary_sha256": hashlib.sha256(binary.read_bytes()).hexdigest(),
                "binary_path": str(binary.relative_to(ROOT)),
            }
            report_path.write_text(json.dumps(report, indent=2) + "\n")
        print("CONTROLLED_PEER_BUILD_OK: 本次固定核心与用户态 DNS；未执行设备验收")
        return 0
    except (OSError, ValueError, KeyError, subprocess.SubprocessError) as error:
        print(f"CONTROLLED_PEER_BUILD_FAILED: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
