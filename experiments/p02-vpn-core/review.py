"""复核已结束的 P02 正式 APK 运行记录；本命令不新增设备验收或回填历史声明。"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/android"))
sys.path.insert(0, str(ROOT / "scripts/verification"))
import live_contract
from evidence_io import json_bytes


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read(path: Path) -> dict:
    return json.loads(path.read_bytes())


def review_case(base: Path, case: str, main_sha: str) -> dict:
    """核对本次身份、完整回执和精确源码快照，再重算行为判据，不能只消费 passed。"""
    device = base / "device" if (base / "device/device-tests.json").exists() else base
    summary = read(device / "device-tests.json")
    result = read(device / "result.json")
    if (
        result["status"] != "passed"
        or summary["scope"] != case
        or summary["mode"] != "live"
    ):
        raise ValueError("没有完整正式运行记录")
    main_name = "app/build/outputs/apk/live/debug/app-live-debug.apk"
    if summary["artifacts"][main_name] != main_sha:
        raise ValueError("历史运行与当前主 APK 字节不同，不能继承")
    sources = read(device / "source-inputs.json")
    if sha(device / "source-inputs.json") != summary["source_inputs_sha256"]:
        raise ValueError("编译与执行来源摘要不符")
    for name, digest in sources["files"].items():
        relative = Path(name)
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("源码快照路径越界")
        path = device / "source-snapshot" / name
        if path.is_symlink() or sha(path) != digest:
            raise ValueError("被测源码快照缺失或字节不符")
        # 测试/执行器可以逐轮演进；实际主程序和原生来源必须与当前工程相同。
        if (
            not name.startswith("app/src/androidTestLive/")
            and name
            not in {
                "scripts/android/android_tools.py",
                "scripts/android/live_contract.py",
                "app/build.gradle.kts",
                "scripts/native/check_review.py",
                "docs/verification/p02/native-review.json",
                "docs/verification/p02/native-review.md",
            }
            and sha(ROOT / name) != digest
        ):
            raise ValueError("实际应用或原生来源已经变化")
    actual = live_contract.instrumentation_results(
        (device / "live-instrumentation.log").read_text(), live_contract.CASES[case]
    )
    if any(
        summary[key] != actual[key]
        for key in ("tests", "failures", "errors", "skipped", "cases")
    ):
        raise ValueError("记录与本次实际用例结果不同")
    checked = None
    if case in {"datapath", "resources", "dns", "policy", "network"}:
        peer = read(base / "peer/report.json")
        if peer["status"] != "stopped" or any(
            peer.get(key) is not True
            for key in ("peer_stopped", "echo_threads_stopped", "dns_socket_stopped")
        ):
            raise ValueError("受控对端未确认关闭")
        run_id = peer["run_id"]
        ready = live_contract.peer_input(base / "peer/ready.json", run_id)
        if sha(base / "peer/ready.json") != summary["peer_report_sha256"]:
            raise ValueError("本次对端收据被替换")
        record = "dns.jsonl" if case == "dns" else "echo.jsonl"
        events = [
            json.loads(line)
            for line in (base / "peer" / record).read_text().splitlines()
        ]
        checker = getattr(
            live_contract,
            {
                "resources": "resource_results",
                "datapath": "datapath_results",
                "dns": "dns_results",
                "policy": "policy_results",
                "network": "network_results",
            }[case],
        )
        checked = checker(read(device / "live-app-report.log"), events, run_id)
        if checked != summary[case] or ready["core_commit"] != live_contract.CORE:
            raise ValueError("行为判据复算与原结果不符")
    elif case in {"lifecycle", "revoke"}:
        checker = (
            live_contract.lifecycle_results
            if case == "lifecycle"
            else live_contract.revoke_results
        )
        checked = checker(read(device / "live-app-report.log"))
        if checked != summary[case]:
            raise ValueError("系统行为复算与原结果不符")
    return {
        "status": "passed",
        "case": case,
        "api": summary["api"],
        "abi": summary["abi"],
        "artifacts": summary["artifacts"],
        "source_inputs_sha256": summary["source_inputs_sha256"],
        "original_summary_sha256": sha(device / "device-tests.json"),
        "checks": actual,
        "observations": checked,
        "device_reexecuted": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        args.out.mkdir(parents=True, exist_ok=False)
        declaration = read(ROOT / "experiments/p02-vpn-core/experiment.json")
        declaration_data = json_bytes(declaration)
        (args.out / "declaration.json").write_bytes(declaration_data)
        (args.out / "declaration-sha256.txt").write_text(
            hashlib.sha256(declaration_data).hexdigest() + "\n"
        )
        inputs = {
            name: sha(ROOT / name)
            for name in (
                "experiments/p02-vpn-core/review.py",
                "scripts/android/live_contract.py",
                "scripts/verification/evidence_io.py",
            )
        }
        (args.out / "review-inputs.json").write_bytes(json_bytes(inputs))
        main_sha = sha(ROOT / "app/build/outputs/apk/live/debug/app-live-debug.apk")
        cases = [
            ("native-contract-03", "bridge"),
            ("datapath-03", "datapath"),
            ("resources-05", "resources"),
            ("dns-02", "dns"),
            ("policy-01", "policy"),
            ("network-03", "network"),
            ("lifecycle-api24-final", "lifecycle"),
            ("lifecycle-api37-final", "lifecycle"),
            ("system-revoke-08", "revoke"),
        ]
        outcomes = []
        for name, case in cases:
            outcomes.append(
                {
                    "run_id": name,
                    **review_case(
                        ROOT / "build/experiments/p02-vpn-core" / name, case, main_sha
                    ),
                }
            )
        output = {
            "schema_version": 1,
            "layer": "static_review_of_bound_device_records",
            "environment_kind": "local_record_review",
            "status": "passed",
            "main_apk_sha256": main_sha,
            "core_commit": live_contract.CORE,
            "records": outcomes,
            "device_reexecuted": False,
            "historical_declarations_rewritten": False,
            "remote_ci": "not_executed",
        }
        if any(sha(ROOT / name) != digest for name, digest in inputs.items()):
            raise ValueError("复核期间执行输入变更")
        (args.out / "review.json").write_text(
            json.dumps(output, ensure_ascii=False, indent=2) + "\n"
        )
        print(
            "P02_RECORD_REVIEW_OK: 9 个已绑定正式主 APK 的完整运行记录；本命令未重跑设备"
        )
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"P02_RECORD_REVIEW_FAILED: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
