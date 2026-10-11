"""检查工作树或暂存树的个人路径、设备标识和常见凭据，不回显敏感值。"""

import argparse
import json
import sys
from pathlib import Path

from evidence_io import Tree
from privacy import findings


def scan(tree, prefixes=()):
    issues = []
    checked = 0
    for name in tree.names(prefixes):
        data = tree.read(name)
        checked += 1
        try:
            content = data.decode("utf-8")
        except UnicodeDecodeError:
            # 二进制也可能嵌入绝对构建路径；检查可见字符串。
            content = data.decode("utf-8", errors="replace")
        try:
            value = json.loads(content) if name.endswith(".json") else content
        except ValueError:
            value = content
        for issue in findings(value):
            issues.append({"path": name, **issue})
    return {
        "status": "failed" if issues else "passed",
        "files_checked": checked,
        "input_tree": tree.tree,
        "issues": issues,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root", type=Path, default=Path(__file__).resolve().parents[2]
    )
    parser.add_argument("--tree", choices=("worktree", "index"), default="worktree")
    parser.add_argument("--paths", nargs="*", default=[])
    args = parser.parse_args()
    try:
        result = scan(Tree(args.repo_root, args.tree), args.paths)
    except (OSError, ValueError) as error:
        result = {"status": "failed", "error": type(error).__name__}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
