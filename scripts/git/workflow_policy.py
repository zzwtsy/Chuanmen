"""读取 GitHub 事件，分类变更，并判定 PR 与 CI 门禁。"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

PR_TITLE = re.compile(
    r"(?:feat|fix|docs|refactor|perf|test|build|ci|style|chore|revert)"
    r"(?:\([^\s()]+\))?!?: [^\s].*"
)
SHA = re.compile(r"[0-9a-f]{40}")
ANDROID_PREFIXES = (
    "app/",
    "contracts/",
    "gradle/",
    "scripts/android/",
    "scripts/catalog/",
    "docs/design/config/",
    "experiments/p02-vpn-core/",
    ".github/",
)
ANDROID_FILES = {
    "gradle.properties",
    "gradlew",
    "gradlew.bat",
    "ruff.toml",
    "docs/verification/p02/native-review.json",
    "docs/verification/p02/native-review.md",
}
DOCUMENT_PREFIXES = ("docs/", ".agents/", "prototype/", "experiments/")
DOCUMENT_FILES = {"README.md", "AGENTS.md", "CONTRIBUTING.md"}


def read_event(path: Path) -> dict:
    """事件必须是 JSON 对象；损坏或缺失的数据不能退化成免检。"""
    event = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(event, dict):
        raise TypeError("GitHub 事件必须是 JSON 对象")
    return event


def validate_pr(event: dict) -> None:
    """标题按数据匹配；目标分支固定为当前项目主干 main。"""
    pr = event["pull_request"]
    if pr["base"]["ref"] != "main":
        raise ValueError("PR 必须以 main 为目标分支")
    title = pr["title"]
    if not isinstance(title, str) or not PR_TITLE.fullmatch(title):
        raise ValueError("PR 标题须符合 type(scope)!: 描述；scope 与 ! 可选")


def android_required(paths: list[str]) -> bool:
    """仅已知的免检路径可省略 Android；构建依赖目录优先于文档目录。"""
    for path in paths:
        if (
            path.startswith(ANDROID_PREFIXES)
            or path in ANDROID_FILES
            or ("/" not in path and path.endswith(".gradle.kts"))
        ):
            return True
        if path.startswith(DOCUMENT_PREFIXES) or path in DOCUMENT_FILES:
            continue
        return True
    return False


def git_output(root: Path, *args: str) -> bytes:
    """Git 参数采用独立 argv；保留失败退出码，不执行 shell。"""
    return subprocess.run(
        ["git", "-C", str(root), *args], check=True, stdout=subprocess.PIPE
    ).stdout


def checked_sha(value: object) -> str:
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise ValueError("事件中缺少有效的 Git SHA")
    return value


def changed_paths(event: dict, event_name: str, root: Path) -> list[str] | None:
    """PR 比较分叉点，push 比较前后 SHA；手动或首推返回全检信号。"""
    if event_name == "workflow_dispatch":
        return None
    if event_name == "pull_request":
        pr = event["pull_request"]
        base = checked_sha(pr["base"]["sha"])
        head = checked_sha(pr["head"]["sha"])
        base = git_output(root, "merge-base", base, head).decode("ascii").strip()
        base = checked_sha(base)
    elif event_name == "push":
        if event["ref"] != "refs/heads/main":
            raise ValueError("统一 CI 的 push 事件只接受 main")
        base = checked_sha(event["before"])
        head = checked_sha(event["after"])
        if base == "0" * 40:
            return None
    else:
        raise ValueError(f"不支持的 CI 事件：{event_name}")
    # 禁用 rename 合并，旧路径作为删除、新路径作为新增均进入分类。
    raw = git_output(root, "diff", "--name-only", "-z", "--no-renames", base, head)
    return [os.fsdecode(path) for path in raw.split(b"\0") if path]


def validate_gate(needs: dict) -> None:
    """必须检查成功；仅明确不需要 Android 时接受其预期 skipped。"""
    if needs["changes"]["result"] != "success":
        raise ValueError("变更识别未成功")
    if needs["verification"]["result"] != "success":
        raise ValueError("验证工作流未成功")
    required = needs["changes"]["outputs"]["android_required"]
    if required not in ("true", "false"):
        raise ValueError("android_required 输出缺失或无效")
    expected = "success" if required == "true" else "skipped"
    if needs["android"]["result"] != expected:
        raise ValueError(f"Android 检查应为 {expected}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("pr", "changes", "gate"))
    args = parser.parse_args()
    try:
        if args.command == "gate":
            validate_gate(json.loads(os.environ["NEEDS_JSON"]))
            print("quality-gate: passed")
        else:
            event = read_event(Path(os.environ["GITHUB_EVENT_PATH"]))
            if args.command == "pr":
                validate_pr(event)
                print("pr-policy: passed")
            else:
                paths = changed_paths(
                    event, os.environ["GITHUB_EVENT_NAME"], Path.cwd()
                )
                required = paths is None or android_required(paths)
                output = f"android_required={str(required).lower()}\n"
                with Path(os.environ["GITHUB_OUTPUT"]).open(
                    "a", encoding="utf-8"
                ) as stream:
                    stream.write(output)
                print(output, end="")
    except (
        KeyError,
        TypeError,
        ValueError,
        OSError,
        subprocess.CalledProcessError,
    ) as exc:
        print(f"workflow-policy: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
