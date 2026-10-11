"""为所选阶段刷新运输清单；不冻结开发目录，不改写已发布证据或暂存区。"""

import argparse
import json
from pathlib import Path

from evidence_io import Tree, digest, json_bytes, relative_name


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root", type=Path, default=Path(__file__).resolve().parents[2]
    )
    parser.add_argument("--tree", choices=("worktree", "index"), default="index")
    parser.add_argument("--stage", default="p00")
    args = parser.parse_args()
    root = args.repo_root.resolve()
    tree = Tree(root, args.tree)
    prefix = "docs/verification/" + relative_name(args.stage)
    manifest_name = prefix + "/manifest.json"
    manifest = tree.object(manifest_name)
    if manifest.get("schema_version") != 3:
        parser.error("需要版本 3 阶段清单")
    names = [entry["path"] for entry in manifest["entries"]] + [manifest_name]
    # 刷新运输校验和前校验发布摘要，不能通过重新计算掩盖被修改的冻结证据。
    for entry in manifest["entries"]:
        tree.read(entry["path"], entry["sha256"])
    handoff = {
        "schema_version": 2,
        "phase": args.stage.upper(),
        "p01_p02_started": False,
        "input_tree": args.tree,
        "scope_prefix": prefix,
        "scope": "所选脱敏阶段证据；正常开发文件不属于运输冻结范围",
        "files": [
            {"path": name, "sha256": digest(tree.read(name))} for name in sorted(names)
        ],
    }
    data = json_bytes(handoff)
    (root / "handoff.json").write_bytes(data)
    sums = {row["path"]: row["sha256"] for row in handoff["files"]}
    sums["handoff.json"] = digest(data)
    (root / "SHA256SUMS").write_text(
        "".join(value + "  " + name + "\n" for name, value in sorted(sums.items()))
    )
    print(
        json.dumps(
            {
                "files": len(names),
                "input_tree": args.tree,
                "next": "暂存 handoff.json、SHA256SUMS，然后运行严格交付检查。",
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
