"""只读审计目录覆盖；平台能力是否可用必须由选定原生版本的证据另行判断。"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE = ROOT / "docs/design/config/assistant-catalog.snapshot.json"


def read_object(path: Path) -> dict:
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        # 已解析 JSON 的结构错误沿用输入校验的 ValueError 契约。
        raise ValueError(f"JSON 顶层必须为对象：{path.name}")  # noqa: TRY004
    return value


def audit(catalog: dict, capability_plan: dict | None) -> dict:
    errors: list[str] = []
    warnings = [
        "PASS 仅表示目录约束一致，不证明官网当前版本未变化。",
        "快照 availability/apply 不是原生能力承诺；热更新与平台适配仍需实证。",
    ]
    fields = catalog.get("fields")
    groups = catalog.get("groups")
    meta = catalog.get("meta")
    if (
        not isinstance(fields, list)
        or not isinstance(groups, list)
        or not isinstance(meta, dict)
    ):
        # 字段类型属于外部 JSON 结构校验，与其他结构错误统一处理。
        raise ValueError("目录必须包含 fields 数组、groups 数组和 meta 对象")  # noqa: TRY004
    if not all(isinstance(f, dict) for f in fields + groups):
        raise ValueError("fields / groups 的每项必须为对象")
    keys = [f.get("key") for f in fields]
    if not all(isinstance(k, str) and k.strip() for k in keys):
        raise ValueError("每个字段需要非空字符串 key")
    for key, count in Counter(keys).items():
        if count > 1:
            errors.append(f"重复配置键：{key}（{count} 次）")
    group_ids = [g.get("id") for g in groups]
    if not all(isinstance(g, str) and g for g in group_ids):
        raise ValueError("分组 id 必须是非空字符串")
    if len(group_ids) != len(set(group_ids)):
        errors.append("分组 id 重复")
    count = Counter(f.get("scope") for f in fields)
    for scope in count:
        if scope not in {"core", "android"}:
            errors.append(f"未知字段作用域：{scope}")
    for key, actual in (
        ("visibleCoreCount", count["core"]),
        ("visibleAndroidCount", count["android"]),
        ("visibleFieldCount", len(fields)),
    ):
        if meta.get(key) != actual:
            errors.append(f"{key} 声明 {meta.get(key)}，实际 {actual}")

    deployed = meta.get("deployed", {})
    source_fields = deployed.get("sourceFields", [])
    if not isinstance(source_fields, list) or not all(
        isinstance(x, str) for x in source_fields
    ):
        raise ValueError("deployed.sourceFields 必须是字符串数组")
    if not source_fields:
        errors.append("缺少官网来源字段集合")
    if len(source_fields) != len(set(source_fields)):
        errors.append("官网来源字段重复")
    source_set = set(source_fields)
    if not str(deployed.get("url", "")).startswith("https://easytier.cn/assistant"):
        errors.append("缺少实际部署助手入口来源")
    for name in ("entrySha256", "bundleSha256"):
        if not re.fullmatch(r"[0-9a-f]{64}", str(deployed.get(name, ""))):
            errors.append(f"部署证据哈希缺失或格式错误：{name}")
    if not meta.get("capturedAtUtc") or not deployed.get("bundleUrl"):
        errors.append("缺少捕获时间或部署 bundle URL")
    if not set(deployed.get("dynamicFlags", [])).issubset(source_set):
        errors.append("动态 flags 表存在没有来源映射的字段")

    covered: set[str] = set()
    for field in fields:
        key = field["key"]
        for prop in ("label", "type", "help", "group", "scope"):
            if not field.get(prop):
                errors.append(f"{key} 缺少 {prop}")
        if "default" not in field:
            errors.append(f"{key} 缺少显式 default（null 也应明确）")
        if field.get("group") not in group_ids:
            errors.append(f"{key} 引用了不存在的组")
        refs = field.get("assistantFields", [])
        if not isinstance(refs, list) or not all(isinstance(x, str) for x in refs):
            raise ValueError(f"{key} 的 assistantFields 必须为字符串数组")
        if field.get("scope") == "core":
            if not refs:
                errors.append(f"{key} 缺少官网可见输入映射")
            covered.update(refs)
            if key.startswith("android.") or key in {
                "instance_name",
                "profile.label",
                "localDisplayName",
            }:
                errors.append(f"本地或范围外字段误入核心表单：{key}")
        elif field.get("scope") == "android":
            if not key.startswith("android."):
                errors.append(f"Android 字段未使用独立命名空间：{key}")
            if refs:
                errors.append(f"Android 自有字段不应伪装为官网核心字段：{key}")

    hidden = meta.get("hiddenAssistantFields", [])
    if not isinstance(hidden, list) or not all(isinstance(h, dict) for h in hidden):
        raise ValueError("hiddenAssistantFields 必须是对象数组")
    hidden_keys = [h.get("key") for h in hidden]
    if len(hidden_keys) != len(set(hidden_keys)):
        errors.append("隐藏字段 key 重复")
    hidden_sources: set[str] = set()
    for item in hidden:
        if item.get("key") in keys:
            errors.append(f"隐藏配置进入可见表单：{item.get('key')}")
        if item.get("ui") != "hidden" or item.get("search") != "hidden":
            errors.append(f"隐藏项的表单和搜索策略不一致：{item.get('key')}")
        if not item.get("reason"):
            errors.append(f"隐藏项缺少理由：{item.get('key')}")
        refs = item.get("assistantFields", [])
        if not isinstance(refs, list) or not all(isinstance(x, str) for x in refs):
            raise ValueError("隐藏项 assistantFields 必须为字符串数组")
        hidden_sources.update(refs)
    overlap = covered & hidden_sources
    if overlap:
        errors.append("来源同时被标为显示和隐藏：" + ", ".join(sorted(overlap)))
    missing = source_set - covered - hidden_sources
    extra = (covered | hidden_sources) - source_set
    if missing:
        errors.append("官网来源缺少显隐决定：" + ", ".join(sorted(missing)))
    if extra:
        errors.append("映射了来源集合以外字段：" + ", ".join(sorted(extra)))
    simple = meta.get("simpleKeys", [])
    expected_simple = {
        "network_identity.network_name",
        "network_identity.network_secret",
        "peer",
    }
    if set(simple) != expected_simple or len(simple) != len(expected_simple):
        errors.append("基础配置输入集合偏离已确认的三个编辑项")
    if not set(simple).issubset(keys):
        errors.append("基础配置引用了不存在的键")

    if capability_plan is not None:
        entries = capability_plan.get("fields", [])
        if not isinstance(entries, list) or not all(
            isinstance(e, dict) for e in entries
        ):
            raise ValueError("能力计划 fields 必须为对象数组")
        planned = [e.get("key") for e in entries]
        if len(planned) != len(set(planned)) or set(planned) != set(keys):
            errors.append("能力计划与当前目录键集合不一致")
        for entry in entries:
            status = entry.get("nativeStatus")
            if status not in {"not_verified", "verified", "blocked", "not_applicable"}:
                errors.append(f"能力状态无效：{entry.get('key')}")
            if status == "verified" and not entry.get("evidence"):
                errors.append(f"声称已验证但没有证据：{entry.get('key')}")
        warnings.append(
            "暂未适配与平台不适用必须分开，特别复核 ipv6_public_addr_auto。"
        )

    return {
        "status": "PASS" if not errors else "FAIL",
        "errors": errors,
        "warnings": warnings,
        "counts": {
            "core_editors": count["core"],
            "android_editors": count["android"],
            "visible_editors": len(fields),
            "groups": len(groups),
            "source_fields": len(source_set),
            "hidden_editors": len(hidden),
        },
        "scope": "目录内部与项目显隐契约；不是官网实时或原生运行验证",
    }


def differences(old: dict, new: dict) -> list[dict]:
    before = {f["key"]: f for f in old["fields"]}
    after = {f["key"]: f for f in new["fields"]}
    result = []
    old_counts = Counter(f["key"] for f in old["fields"])
    new_counts = Counter(f["key"] for f in new["fields"])
    for key in sorted(set(before) | set(after)):
        if (
            max(old_counts[key], new_counts[key]) > 1
            and old_counts[key] != new_counts[key]
        ):
            result.append(
                {
                    "key": key,
                    "change": "occurrences-changed",
                    "before_count": old_counts[key],
                    "after_count": new_counts[key],
                }
            )
        if key not in before:
            result.append({"key": key, "change": "added"})
        elif key not in after:
            result.append({"key": key, "change": "removed"})
        else:
            changed = [
                prop
                for prop in sorted(set(before[key]) | set(after[key]))
                if (prop in before[key]) != (prop in after[key])
                or before[key].get(prop) != after[key].get(prop)
            ]
            if changed:
                result.append({"key": key, "change": "changed", "properties": changed})
    for prop in sorted(set(old["meta"]["deployed"]) | set(new["meta"]["deployed"])):
        if old["meta"]["deployed"].get(prop) != new["meta"]["deployed"].get(prop):
            result.append({"key": f"meta.deployed.{prop}", "change": "changed"})
    for prop in sorted((set(old["meta"]) | set(new["meta"])) - {"deployed"}):
        if old["meta"].get(prop) != new["meta"].get(prop):
            result.append({"key": f"meta.{prop}", "change": "changed"})
    if old.get("groups") != new.get("groups"):
        result.append({"key": "groups", "change": "changed"})
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--catalog", type=Path, default=BASELINE, help="待审计 JSON；默认本包快照"
    )
    parser.add_argument(
        "--baseline", type=Path, help="可选比较基线；有差异时退出 1，不输出秘密值"
    )
    parser.add_argument(
        "--capability-plan",
        type=Path,
        help="可选显式能力台账；外部候选默认不套用项目台账",
    )
    parser.add_argument("--report", type=Path, help="可选报告文件；不修改输入目录")
    args = parser.parse_args()
    try:
        catalog = read_object(args.catalog)
        plan_path = (
            args.capability_plan
            or ROOT / "docs/design/config/android-capability-plan.json"
        )
        plan = (
            read_object(plan_path)
            if args.capability_plan or args.catalog.resolve() == BASELINE.resolve()
            else None
        )
        result = audit(catalog, plan)
        result["catalog_sha256"] = hashlib.sha256(args.catalog.read_bytes()).hexdigest()
        result["capability_plan_checked"] = plan is not None
        if plan is None:
            result["warnings"].append(
                "外部候选未自动套用能力台账；需要时使用 --capability-plan 明确指定。"
            )
        elif plan.get("catalogSha256") != result["catalog_sha256"]:
            result["errors"].append(
                "能力台账绑定的目录哈希不符，需用对应版本的证据计划"
            )
            result["status"] = "FAIL"
        if args.baseline:
            baseline = read_object(args.baseline)
            baseline_result = audit(baseline, None)
            if baseline_result["errors"]:
                raise ValueError("比较基线自身无效，请先修复其目录结构")
            result["differences"] = differences(baseline, catalog)
            if result["differences"]:
                result["status"] = "REVIEW_REQUIRED" if not result["errors"] else "FAIL"
        code = 0 if result["status"] == "PASS" else 1
    except (OSError, ValueError, TypeError, KeyError) as exc:
        result = {
            "status": "INPUT_ERROR",
            "errors": [str(exc)],
            "scope": "没有完成审计",
        }
        code = 2
    rendered = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.report:
        try:
            if args.report.resolve() in {
                args.catalog.resolve(),
                args.baseline.resolve() if args.baseline else BASELINE.resolve(),
            }:
                raise ValueError("报告路径不能覆盖输入目录")
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(rendered, encoding="utf-8")
        except (OSError, ValueError) as exc:
            print(f"无法写入报告：{exc}", file=sys.stderr)
            return 2
    print(rendered, end="")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
