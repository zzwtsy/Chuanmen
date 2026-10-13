"""检查交付包的实际文件与链接；不把文档检查等同于原生实现验收。"""

from __future__ import annotations

import argparse
import ast
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote

sys.dont_write_bytecode = True
from catalog.audit_config_catalog import audit, read_object
from verification.evidence_io import Tree, export_index, relative_name

ROOT = Path(__file__).resolve().parents[1]
PHASES = [
    "p00-feasibility",
    "p01-foundation",
    "p02-vpn-core",
    "p03-configuration",
    "p04-observability-ui",
    "p05-advanced-networking",
    "p06-hardening",
    "p07-release",
]


class PageInventory(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.ids: list[str] = []
        self.scripts: list[str] = []
        self.html = False
        self.language = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if values.get("id"):
            self.ids.append(values["id"])
        if tag == "script" and values.get("src"):
            self.scripts.append(values["src"])
        if tag == "html":
            self.html = True
            self.language = values.get("lang")


def main() -> int:
    global ROOT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--tree", choices=("worktree", "index"), default="worktree")
    parser.add_argument("--strict", action="store_true")
    args = parser.parse_args()
    ROOT = args.root.resolve()
    if args.tree == "index":
        with tempfile.TemporaryDirectory(prefix="chuanmen-handoff-index-") as folder:
            export_index(ROOT, folder)
            command = [
                sys.executable,
                str(Path(folder) / "scripts/validate_handoff.py"),
                "--root",
                folder,
            ]
            if args.strict:
                command.append("--strict")
            completed = subprocess.run(
                command, check=False, capture_output=True, text=True
            )
            if completed.stderr:
                print(completed.stderr, file=sys.stderr, end="")
            result = json.loads(completed.stdout)
            result["input_tree"] = "git_index"
            print(json.dumps(result, ensure_ascii=False, indent=2))
            return completed.returncode
    files = []
    for prefix in ("docs", "prototype", "scripts", "experiments"):
        files.extend(
            p
            for p in (ROOT / prefix).rglob("*")
            if p.is_file() and ".local" not in p.parts and "__pycache__" not in p.parts
        )
    files.extend(
        ROOT / name
        for name in (
            "README.md",
            "AGENTS.md",
            "CONTRIBUTING.md",
            "handoff.json",
            "SHA256SUMS",
        )
        if (ROOT / name).is_file()
    )
    files.extend(
        p
        for p in (ROOT / ".agents/skills/easytier-config-audit").rglob("*")
        if p.is_file()
    )

    files.extend(
        p
        for p in (ROOT / ".agents/skills/verification-evidence").rglob("*")
        if p.is_file()
    )

    errors: list[str] = []
    warnings: list[str] = []
    required = [
        "README.md",
        "docs/plan/ROADMAP.md",
        "AGENTS.md",
        "docs/design/overall-design.md",
        "docs/design/technical-architecture.md",
        "docs/development/ai-friendly-android.md",
        "docs/development/agent-skills-strategy.md",
        "docs/design/requirements-traceability.md",
        "prototype/index.html",
        "prototype/README.md",
        "prototype/source/chuanmen-android-config.fragment.html",
        "docs/design/config/README.md",
        "docs/design/config/assistant-catalog.snapshot.json",
        "docs/design/config/android-capability-plan.json",
        ".agents/skills/easytier-config-audit/SKILL.md",
        "docs/development/task-brief.md",
        "docs/verification/README.md",
        "docs/research/sources-android.json",
        "docs/research/sources-agents.json",
    ]
    required += [f"docs/plan/{p}.md" for p in PHASES]
    for item in required:
        if not (ROOT / item).is_file():
            errors.append(f"缺少交付物：{item}")
    actual_phases = sorted(p.stem for p in (ROOT / "docs/plan").glob("p*.md"))
    if actual_phases != sorted(PHASES):
        errors.append("阶段文件集合不等于约定八阶段")

    markdown_count = 0
    link_count = 0
    for path in (p for p in files if p.suffix == ".md"):
        if ".git" in path.parts:
            continue
        markdown_count += 1
        content = path.read_text(encoding="utf-8")
        if len(re.findall(r"^```", content, re.MULTILINE)) % 2:
            errors.append(f"代码围栏未配对：{path.relative_to(ROOT)}")
        prose = re.sub(r"```[^\n]*\n.*?```", "", content, flags=re.DOTALL)
        for match in re.finditer(r"\[[^\]]*\]\(([^)]+)\)", prose):
            target = match.group(1).strip().split(' "', 1)[0]
            if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", target) or target.startswith("#"):
                continue
            relative = unquote(target.split("#", 1)[0])
            if not relative:
                continue
            link_count += 1
            resolved = (path.parent / relative).resolve()
            if not resolved.is_relative_to(ROOT.resolve()):
                errors.append(f"链接指向包外：{path.relative_to(ROOT)} → {target}")
            elif not resolved.exists():
                errors.append(f"链接目标不存在：{path.relative_to(ROOT)} → {target}")
        if path.name == "SKILL.md" and ("[TODO" in content or "TODO:" in content):
            errors.append("技能仍包含初始化占位内容")

    json_count = 0
    for path in (p for p in files if p.suffix == ".json"):
        if ".git" in path.parts:
            continue
        json_count += 1
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            errors.append(f"JSON 无效：{path.relative_to(ROOT)}：{exc}")
    for path in (
        p
        for p in files
        if p.suffix == ".py"
        and "inputs/snapshots" not in p.as_posix()
        and "evidence/reviews" not in p.as_posix()
    ):
        try:
            ast.parse(path.read_text(encoding="utf-8"), filename=path.name)
        except SyntaxError as exc:
            errors.append(f"Python 语法错误：{path.name}：{exc}")

    catalog_result = None
    try:
        catalog_result = audit(
            read_object(ROOT / "docs/design/config/assistant-catalog.snapshot.json"),
            read_object(ROOT / "docs/design/config/android-capability-plan.json"),
        )
        errors.extend(catalog_result["errors"])
    except (OSError, ValueError, TypeError, KeyError) as exc:
        errors.append(f"配置审计未完成：{exc}")

    page_path = ROOT / "prototype/index.html"
    asset_count = 0
    if page_path.exists():
        page = page_path.read_text(encoding="utf-8")
        inventory = PageInventory()
        inventory.feed(page)
        if (
            not inventory.html
            or inventory.language != "zh-CN"
            or not page.lower().startswith("<!doctype html>")
        ):
            errors.append("导出页面不是完整中文 HTML 文档")
        if inventory.ids.count("easytier-config") != 1:
            errors.append("原型根节点缺失或重复")
        if len(inventory.ids) != len(set(inventory.ids)):
            errors.append("原型静态节点 id 重复")
        for src in inventory.scripts:
            if re.match(r"^(https?:)?//", src):
                errors.append(f"离线原型仍依赖网络脚本：{src}")
            elif not (page_path.parent / src).is_file():
                errors.append(f"离线资源不存在：{src}")
        if "window.openai" in page or re.search(
            r"\b(?:fetch|XMLHttpRequest|WebSocket)\s*\(", page
        ):
            errors.append("原型出现宿主或网络请求依赖，需人工复核")
        for label in ("Chuanmen", "基础配置", "完整配置", "交互原型", "重命名"):
            if label not in page:
                errors.append(f"原型缺少预期基线文字：{label}")
    try:
        for asset in read_object(ROOT / "prototype/assets/manifest.json")["assets"]:
            path = ROOT / "prototype/assets" / asset["file"]
            if hashlib.sha256(path.read_bytes()).hexdigest() != asset["sha256"]:
                errors.append(f"第三方资源哈希不符：{asset['file']}")
            asset_count += 1
    except (OSError, ValueError, KeyError) as exc:
        errors.append(f"离线资源清单无效：{exc}")

    checksum_count = 0
    sums = ROOT / "SHA256SUMS"
    if sums.is_file():
        for line in sums.read_text(encoding="utf-8").splitlines():
            digest, relative = line.split("  ", 1)
            path = (ROOT / relative).resolve()
            if not path.is_relative_to(ROOT.resolve()) or not path.is_file():
                errors.append(f"校验和路径无效：{relative}")
            elif hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                errors.append(f"文件哈希不符：{relative}")
            checksum_count += 1
    else:
        warnings.append("尚无 SHA256SUMS；开发阶段可继续，正式打包前必须生成并复核。")
    if args.strict:
        if not sums.is_file():
            errors.append("严格交付缺少 SHA256SUMS")
        try:
            handoff = read_object(ROOT / "handoff.json")
            prefix = relative_name(handoff["scope_prefix"])
            if handoff.get("schema_version") != 2 or not prefix.startswith(
                "docs/verification/"
            ):
                raise ValueError("需要限定阶段范围的运输清单")
            rows = handoff["files"]
            declared = {item["path"]: item["sha256"] for item in rows}
            if len(declared) != len(rows) or any(
                not name.startswith(prefix + "/") for name in declared
            ):
                raise ValueError("运输文件重复或越过阶段边界")
            tree = Tree(ROOT)
            actual = set(tree.names([prefix]))
            if actual != set(declared):
                errors.append(
                    "阶段运输集合未闭合：" + str(sorted(actual ^ set(declared)))
                )
            sum_entries = dict(
                line.split("  ", 1)[::-1] for line in sums.read_text().splitlines()
            )
            if set(sum_entries) != set(declared) | {"handoff.json"}:
                errors.append("阶段 SHA256SUMS 文件集合未闭合")
            for name, expected_hash in declared.items():
                tree.read(name, expected_hash)
            if handoff["phase"] != "P00" or handoff["p01_p02_started"] is not False:
                errors.append("交付阶段声明不一致")
        except (OSError, ValueError, KeyError, TypeError) as exc:
            errors.append("阶段运输清单无效：" + type(exc).__name__)
    warnings.append("本检查不执行 Android 构建、真实网络、浏览器像素或商店审核。")
    result = {
        "input_tree": "explicit_delivery_tree",
        "status": "PASS" if not errors else "FAIL",
        "errors": errors,
        "warnings": warnings,
        "counts": {
            "phase_files": len(actual_phases),
            "markdown_files": markdown_count,
            "local_links": link_count,
            "json_files": json_count,
            "vendored_assets_and_licenses": asset_count,
            "checksums": checksum_count,
        },
        "catalog": catalog_result["counts"] if catalog_result else None,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
