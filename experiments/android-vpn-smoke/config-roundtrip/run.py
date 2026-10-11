"""执行固定核心真实配置解析/转储/恢复，保留完整读回值；不启动任何网络。"""

import argparse
import hashlib
import json
import subprocess
import tomllib
from datetime import UTC, datetime
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build-report", type=Path, required=True)
    parser.add_argument("--fixture", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("禁止覆盖已有证据")
    build = json.loads(args.build_report.read_text())
    fixture = json.loads(args.fixture.read_text())
    binary = Path(build["binary_path"])
    if (
        build.get("status") != "passed"
        or build.get("dependency_identity_subset_verified") is not True
        or sha(binary) != build["binary_sha256"]
    ):
        parser.error("构建或固定依赖/二进制证据不符")
    inputs = fixture["cases"]
    result = subprocess.run(
        [str(binary)],
        check=False,
        input="".join(json.dumps(c, ensure_ascii=False) + "\n" for c in inputs),
        text=True,
        capture_output=True,
        timeout=60,
    )
    rows = [json.loads(line) for line in result.stdout.splitlines()]
    failures = []
    for actual, case in zip(rows, inputs):
        correct = (
            actual.get("name") == case["name"]
            and actual.get("accepted") == case["expected_acceptance"]
        )
        if actual["accepted"]:
            correct &= actual.get("roundtrip_equal") is True
            # 条件：显式 flags；操作：真实核心读回；结果：用户给出的值与 Rust 字段值一致，包含 false 和 u64。
            supplied = tomllib.loads(case["toml"]).get("flags", {})
            correct &= all(
                actual["effective"]["flags"].get(key) == value
                for key, value in supplied.items()
            )
        if not correct:
            failures.append({"name": case["name"], "actual": actual})
    report = {
        "task": "P00-03",
        "layer": "host_core_config_roundtrip",
        "network_started": False,
        "status": "passed"
        if not failures and len(rows) == len(inputs) and result.returncode == 0
        else "failed",
        "recorded_at_utc": datetime.now(UTC).isoformat(),
        "commit": fixture["candidate_commit"],
        "fixture_sha256": sha(args.fixture),
        "binary_sha256": sha(binary),
        "build_report_sha256": sha(args.build_report),
        "runner_sha256": sha(Path(__file__)),
        "exit_code": result.returncode,
        "stderr": result.stderr,
        "cases": rows,
        "failures": failures,
    }
    with args.output.open("x") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write("\n")
    print(
        json.dumps(
            {
                "status": report["status"],
                "cases": len(rows),
                "failures": len(failures),
                "output": str(args.output),
            }
        )
    )
    return 0 if report["status"] == "passed" else 2


if __name__ == "__main__":
    raise SystemExit(main())
