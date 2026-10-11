"""检查所选证据、判据与阶段声明；静态通过不代表执行了真机实验。"""

from __future__ import annotations

import argparse
import json
import sys
from itertools import pairwise
from pathlib import Path

from evidence_io import Tree, json_bytes, relative_name, write_new
from privacy import findings

ROOT = Path(__file__).resolve().parents[2]
NATIVE_CHECKS = (
    "tun_ownership",
    "protect_callbacks",
    "tcp_roundtrip",
    "udp_roundtrip",
    "loop_prevention",
    "cleanup",
    "permission_errors",
    "address_routes",
    "performance",
)


def pointer(value, path):
    if path == "":
        return value
    if not isinstance(path, str) or not path.startswith("/"):
        raise ValueError("判据位置必须是 JSON pointer")
    for part in path[1:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        value = value[int(part)] if isinstance(value, list) else value[part]
    return value


def evaluate(value, operation, expected=None):
    if operation == "equals":
        return value == expected
    if operation == "length":
        return len(value) == expected
    if operation == "at_least":
        return (
            isinstance(value, (int, float))
            and not isinstance(value, bool)
            and value >= expected
        )
    if operation == "all_passed":
        return bool(value) and all(row.get("status") == "passed" for row in value)
    if operation == "hash_pairs":
        return bool(value) and all(
            row.get("status") == "passed"
            and row.get("request_sha256")
            and row["request_sha256"] == row.get("response_sha256")
            for row in value
        )
    if operation == "roundtrip_cases":
        return bool(value) and all(
            not row.get("accepted")
            or (
                row.get("roundtrip_equal") is True
                and row.get("effective") == row.get("restored")
            )
            for row in value
        )
    if operation == "not_empty":
        return bool(value)
    if operation == "accepted_count":
        return sum(row.get("accepted") is True for row in value) == expected
    if operation in (
        "ownership_cycles",
        "host_roundtrips",
        "loop_observations",
        "performance_samples",
    ):
        return globals()[operation](value)
    raise ValueError("未知判据操作")


def ownership_cycles(observation):
    rounds = observation["rounds"]
    return len(rounds) >= 20 and all(
        row["closed"]["native_state"] == "released"
        and row["closed"]["valid_before_owner_close"] is True
        and row["closed"]["valid_after_owner_close"] is False
        and row.get("fd_after", row.get("fd")) <= observation["baseline_fd"]
        and row.get("threads_after", row.get("threads"))
        <= observation["baseline_threads"]
        for row in rounds
    )


def host_roundtrips(record):
    host = {
        (row["protocol"], row["request_sha256"]) for row in record["host_echo_records"]
    }
    return all(
        (protocol, row["request_sha256"]) in host
        for protocol in ("tcp", "udp")
        for row in record["observations"][protocol]
    )


def loop_observations(record):
    observation = record["observations"]["observations"]
    outer = record["controlled_topology"]["host_bind"].split(":")[0]
    return (
        observation["read_packets"] > 0
        and observation["write_packets"] > 0
        and outer not in observation["destinations"]
    )


def performance_samples(observation):
    import math

    for name in ("tcp", "udp"):
        row = observation[name]
        if row["duration_seconds"] <= 0 or row["bytes"] <= 0 or len(row["samples"]) < 2:
            return False
        if not math.isclose(
            row["goodput_mbps"],
            row["bytes"] * 8 / row["duration_seconds"] / 1e6,
            rel_tol=1e-9,
        ):
            return False
        if row["request_sha256"] != row["response_sha256"]:
            return False
    idle = observation["idle"]
    samples = idle["samples"]
    if len(samples) != 19 or any(s["energy_nwh"] is not None for s in samples):
        return False
    if any(b["elapsed_ns"] <= a["elapsed_ns"] for a, b in pairwise(samples)):
        return False
    estimate = (
        (samples[0]["charge_uah"] - samples[-1]["charge_uah"])
        * (samples[0]["voltage_mv"] + samples[-1]["voltage_mv"])
        / 2000000
    )
    return math.isclose(estimate, idle["energy_mwh_estimate"], rel_tol=1e-9)


def check(tree, stage="p00"):
    prefix = "docs/verification/" + relative_name(stage)
    manifest_name = prefix + "/manifest.json"
    errors, gaps = [], []
    checked, records = 0, {}
    manifest = {}
    try:
        manifest = tree.object(manifest_name)
        if manifest.get("schema_version") != 3 or manifest.get("stage") != stage:
            raise ValueError("需要版本 3 的所选阶段清单")
        entries = manifest["entries"]
        names = [item["path"] for item in entries]
        if len(names) != len(set(names)) or manifest_name in names:
            raise ValueError("证据条目重复或清单包含自身")
        claims = manifest["claims"]
        if not claims:
            raise ValueError("阶段缺少结论")
        for item in entries:
            name = relative_name(item["path"])
            if not name.startswith(prefix + "/"):
                raise ValueError("阶段证据不能冻结开发目录")
            if (
                not item.get("role")
                or not item.get("claim_ids")
                or not set(item["claim_ids"]) <= set(claims)
            ):
                raise ValueError("文件缺少有效用途或结论归属：" + name)
            data = tree.read(name, item["sha256"])
            if findings(
                json.loads(data)
                if name.endswith(".json")
                else data.decode("utf-8", errors="replace")
            ):
                errors.append("发布证据存在隐私问题：" + name)
            checked += 1
            if name.endswith(".json"):
                records[name] = json.loads(data)
        actual = set(tree.names([prefix]))
        if actual != set(names) | {manifest_name}:
            errors.append(
                "阶段文件集合未闭合："
                + ", ".join(sorted(actual ^ (set(names) | {manifest_name})))
            )
        for claim_id, claim in claims.items():
            if claim.get("status") != "passed":
                gaps.append("未通过：" + claim_id)
                continue
            if (
                not claim.get("scope")
                or not claim.get("evidence")
                or not claim.get("criteria")
            ):
                errors.append("结论缺少范围、证据或判据：" + claim_id)
                continue
            if not set(claim["evidence"]) <= set(names):
                errors.append("结论引用未发布证据：" + claim_id)
            for criterion in claim["criteria"]:
                record = criterion["record"]
                if record not in claim["evidence"] or record not in records:
                    raise ValueError("判据引用未归属该结论")
                value = pointer(records[record], criterion["pointer"])
                if not evaluate(
                    value, criterion["operation"], criterion.get("expected")
                ):
                    errors.append("判据失败：" + claim_id + "/" + criterion["id"])
        # 所有真机成功记录均须绑定对应构建，不能借用新 APK 的成功结果。
        for name, record in records.items():
            if not isinstance(record, dict) or not str(
                record.get("layer", "")
            ).startswith("android_native_"):
                continue
            if (
                record.get("status") != "passed"
                or record.get("environment_kind") != "physical_device"
            ):
                continue
            build_name = record.get("publication", {}).get("build_ref")
            build = records.get(build_name)
            if not isinstance(build, dict):
                errors.append("真机记录缺少已发布构建来源：" + name)
                continue
            if not record.get("apk_sha256") or record["apk_sha256"] != build.get(
                "apk_sha256"
            ):
                errors.append("设备与构建 APK 身份不一致：" + name)
            if (
                record.get("installed_apk_sha256")
                and record["installed_apk_sha256"] != record["apk_sha256"]
            ):
                errors.append("实际安装 APK 与被测身份不一致：" + name)
            libraries = {row.get("sha256") for row in build.get("libraries", [])}
            recorded_libraries = {
                row.get("sha256") for row in record.get("libraries", [])
            }
            recorded_digest = record.get("native_library_sha256")
            if (recorded_digest and recorded_digest not in libraries) or (
                not recorded_digest
                and (not recorded_libraries or not recorded_libraries <= libraries)
            ):
                errors.append("设备原生库与构建身份不一致：" + name)
        for name, record in records.items():
            if (
                isinstance(record, dict)
                and record.get("environment_kind") == "physical_device"
                and str(record.get("layer", "")).startswith("android_native")
                and record.get("core_commit")
                and record["core_commit"] != manifest["upstream"]["candidate_commit"]
            ):
                errors.append("设备核心版本不一致：" + name)
        if stage == "p00":
            if set(claims) != {f"P00-{i:02}" for i in range(1, 9)}:
                errors.append("P00 结论集合不完整")
            native = manifest["native_acceptance"]
            if (
                native.get("layer") != "android_native"
                or native.get("status") != "passed"
            ):
                gaps.append("缺少真实原生验收")
            if native.get("environment_kind") != "physical_device":
                gaps.append("模拟器不能替代真机")
            if native.get("core_commit") != manifest["upstream"]["candidate_commit"]:
                gaps.append("核心版本不一致")
            for tested in native.get("tested_artifacts", []):
                device = records.get(tested["device_report"], {})
                build = records.get(tested["build_report"], {})
                if (
                    device.get("apk_sha256") != tested["apk_sha256"]
                    or build.get("apk_sha256") != tested["apk_sha256"]
                ):
                    errors.append("新产物不能继承历史验收身份")
            for key in NATIVE_CHECKS:
                item = native.get("checks", {}).get(key, {})
                name = item.get("evidence_path")
                record = records.get(name, {})
                if (
                    item.get("status") != "passed"
                    or item.get("layer") != "android_native"
                ):
                    gaps.append("未验证：" + key)
                if not record or record.get("status") != "passed":
                    gaps.append("缺少通过观察：" + key)
                if not str(record.get("layer", "")).startswith("android_native"):
                    gaps.append("平台记录不能替代原生观察：" + key)
                if record.get("environment_kind") != "physical_device":
                    gaps.append("缺少真机观察：" + key)
                if item.get("evidence_sha256") != next(
                    (e["sha256"] for e in entries if e["path"] == name), None
                ):
                    gaps.append("原生门槛摘要不一致：" + key)
                if key in ("tcp_roundtrip", "udp_roundtrip", "cleanup") and (
                    type(item.get("runs")) is not int
                    or item["runs"] < 20
                    or item.get("failed") != 0
                ):
                    gaps.append("未达到 20 轮零失败：" + key)
            if manifest["phase"].get("status") == "verified" and (
                gaps or any(c.get("status") != "passed" for c in claims.values())
            ):
                errors.append("阶段完成声明与证据冲突")
            if (
                manifest["upstream"]["experimental_patches"].get("production_approved")
                is not False
            ):
                errors.append("实验补丁不能获得生产批准")
    except (OSError, ValueError, KeyError, TypeError, IndexError) as error:
        # 隐私值不进入异常输出；路径和判据诊断在上述分支显式生成。
        errors.append("清单或证据结构无效：" + type(error).__name__)
    return {
        "integrity": "failed" if errors else "passed",
        "files_checked": checked,
        "input_tree": tree.tree,
        "delivery_ready": not errors,
        "stage_ready": not errors and not gaps,
        "errors": errors,
        "native_gaps": gaps,
        "reproduction": manifest.get("reproduction", {}),
        "scope": "所选证据的完整性和判据检查；不执行构建、真机或发行验收",
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=ROOT)
    parser.add_argument("--tree", choices=("worktree", "index"), default="worktree")
    parser.add_argument("--stage", default="p00")
    parser.add_argument("--require-stage", action="store_true")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    try:
        result = check(Tree(args.repo_root, args.tree), args.stage)
        if args.out:
            write_new(args.out, json_bytes(result), args.repo_root)
    except (OSError, ValueError):
        result = {
            "integrity": "failed",
            "errors": ["输入树或输出位置无效"],
            "stage_ready": False,
        }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return (
        1
        if result["integrity"] != "passed"
        else 2
        if args.require_stage and not result["stage_ready"]
        else 0
    )


if __name__ == "__main__":
    sys.exit(main())
