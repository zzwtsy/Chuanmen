"""预览过期且关闭的本地运行；仅显式 --apply 删除 build/experiments 内候选。"""

import argparse
import json
import shutil
import time
from pathlib import Path

from evidence_io import relative_name


def candidates(root, days=7, now=None):
    base = Path(root).resolve() / "build/experiments"
    now = now or time.time()
    selected = []
    if not base.is_dir() or base.is_symlink():
        return selected
    for experiment in base.iterdir():
        if not experiment.is_dir() or experiment.is_symlink():
            continue
        for run in experiment.iterdir():
            if not run.is_dir() or run.is_symlink():
                continue
            receipt = run / "lifecycle.json"
            if not receipt.is_file() or receipt.is_symlink():
                continue
            try:
                state = json.loads(receipt.read_bytes())
                if state != {
                    "experiment_id": experiment.name,
                    "run_id": run.name,
                    "state": "closed",
                }:
                    continue
                # 任意最近写入说明仍有采集活动；不能只根据目录创建时间删除。
                files = list(run.rglob("*"))
                if any(p.is_symlink() for p in files):
                    continue
                latest = max([run.stat().st_mtime] + [p.stat().st_mtime for p in files])
                if latest <= now - days * 86400:
                    selected.append(run.relative_to(Path(root).resolve()).as_posix())
            except (OSError, ValueError, TypeError):
                continue
    return sorted(selected)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--repo-root", type=Path, default=Path(__file__).resolve().parents[2]
    )
    parser.add_argument("--days", type=int, default=7)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    if args.days < 1:
        parser.error("保留天数必须大于零")
    paths = candidates(args.repo_root, args.days)
    if args.apply:
        for name in paths:
            path = args.repo_root.resolve() / relative_name(name)
            if name not in candidates(args.repo_root, args.days):
                raise ValueError("清理期间运行重新活跃")
            shutil.rmtree(path)
    print(
        json.dumps(
            {"mode": "deleted" if args.apply else "preview", "candidates": paths},
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
