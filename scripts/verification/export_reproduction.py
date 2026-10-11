"""导出当前可维护的探针工作流，不声称恢复历史 APK 的精确源码版本。"""

import argparse
import json
from pathlib import Path

from evidence_io import Tree, digest, json_bytes, relative_name


def export_sources(tree, destination, experiment_id="android-vpn-smoke"):
    destination = Path(destination).resolve()
    if destination.exists() or destination.is_relative_to(tree.root):
        raise ValueError("必须使用仓库外尚不存在的目录")
    experiment_id = relative_name(experiment_id)
    if "/" in experiment_id or experiment_id == ".":
        raise ValueError("实验身份必须是单个目录名称")
    prefix = "experiments/" + experiment_id
    try:
        declaration = tree.object(prefix + "/experiment.json")
    except (OSError, ValueError):
        raise ValueError("探针声明缺失或无效") from None
    if (
        not isinstance(declaration, dict)
        or declaration.get("experiment_id") != experiment_id
    ):
        raise ValueError("实验声明身份不符")
    reproduction = declaration.get("reproduction")
    inputs = reproduction.get("inputs") if isinstance(reproduction, dict) else None
    if not isinstance(inputs, dict) or any(
        not isinstance(key, str) or not key for key in inputs
    ):
        raise ValueError("复现输入声明缺失或无效")
    sources = tree.names([prefix])
    if prefix + "/experiment.json" not in sources:
        raise ValueError("探针声明未纳入所选源码树")
    extra_inputs = {relative_name(name) for name in inputs.values()}
    if not extra_inputs <= set(tree.names()):
        raise ValueError("复现输入未纳入所选源码树")
    names = sorted(set(sources) | extra_inputs)
    # 先读取并验证全部输入；任何缺项均不创建半份导出。
    verified = {name: tree.read(name) for name in names}
    destination.mkdir(parents=True)
    for name, data in verified.items():
        path = destination / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    receipt = {
        "experiment_id": experiment_id,
        "source_files": {name: digest(data) for name, data in verified.items()},
        "scope": "current_maintained_workflow",
        "historical_source_identity": "not_claimed",
        "experiment_executed": False,
    }
    (destination / "input-receipt.json").write_bytes(json_bytes(receipt))
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root", type=Path, default=Path(__file__).resolve().parents[2]
    )
    parser.add_argument("--tree", choices=("worktree", "index"), default="worktree")
    parser.add_argument(
        "--experiment", default="android-vpn-smoke", help="experiments 下的实验身份"
    )
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = export_sources(
            Tree(args.repo_root, args.tree), args.out, args.experiment
        )
    except (OSError, ValueError):
        parser.exit(1, "导出失败：输入缺失、位置无效或输出已存在。\n")
    print(
        json.dumps(
            {
                "status": "passed",
                "files": len(result["source_files"]),
                "experiment_executed": False,
            }
        )
    )


if __name__ == "__main__":
    main()
