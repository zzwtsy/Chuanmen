"""检查工作树或暂存树的常见隐私信息；只报告位置和规则，不回显敏感值。"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path, PurePosixPath

HOME = re.compile(r'/(?:home|Users)/[^/\s"\'<>\\]+(?:/|(?=$|[\s"\'<>]))')
WINDOWS_HOME = re.compile(r'(?i)[A-Z]:[\\/]+Users[\\/]+[^\\/\s"\'<>]+')
PATTERNS = {
    "personal_home_path": HOME,
    "windows_home_path": WINDOWS_HOME,
    "private_key": re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
    "credential_url": re.compile(r"https?://[^/\s:@]+:[^/\s@]+@"),
    "access_token": re.compile(
        r"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|AKIA[A-Z0-9]{16}|sk-(?:proj-)?[A-Za-z0-9_-]{32,})\b"
    ),
}
IDENTIFIER_FIELDS = {
    "serial",
    "serial_sha256",
    "device_serial",
    "machine_hostname",
    "host_username",
}
PLACEHOLDER = re.compile(r"^(?:<[^>]+>|\$\{[A-Z_]+\}|device-\d+|host-\d+)$")


def findings(value, location="$"):
    """只报告字段位置；合成配置 hostname 不等同于宿主身份字段。"""
    issues = []
    if isinstance(value, dict):
        for key, item in value.items():
            child = location + "." + key
            if (
                key in IDENTIFIER_FIELDS
                and item
                and not (isinstance(item, str) and PLACEHOLDER.fullmatch(item))
            ):
                issues.append(
                    {"field": child, "rule": "persistent_device_or_host_identifier"}
                )
            if key == "uname" and isinstance(item, str):
                parts = item.split()
                if (
                    len(parts) > 2
                    and parts[0] in ("Linux", "Darwin")
                    and not PLACEHOLDER.fullmatch(parts[1])
                ):
                    issues.append({"field": child, "rule": "uname_host_identifier"})
            if key in ("host_bind", "peer_host", "physical_device_ip") and isinstance(
                item, str
            ):
                host = item.split(":")[0]
                if host and not (
                    host.startswith(
                        ("<", "$", "127.", "192.0.2.", "198.51.100.", "203.0.113.")
                    )
                    or host in ("localhost", "0.0.0.0")
                ):
                    issues.append({"field": child, "rule": "real_network_endpoint"})
            issues.extend(findings(item, child))
    elif isinstance(value, list):
        for i, item in enumerate(value):
            issues.extend(findings(item, location + "[" + str(i) + "]"))
    elif isinstance(value, str):
        for rule, pattern in PATTERNS.items():
            if pattern.search(value):
                issues.append({"field": location, "rule": rule})
        # 命令内即使 serial 字段缺失，也不能发布真实 ADB 选择器。
        if re.search(r"\badb\s+-s\s+(?!<|\$\{)\S+", value):
            issues.append({"field": location, "rule": "adb_device_identifier"})
    if isinstance(value, list):
        for i, item in enumerate(value[:-1]):
            if (
                item in ("-s", "--serial")
                and isinstance(value[i + 1], str)
                and not PLACEHOLDER.fullmatch(value[i + 1])
            ) and any(
                isinstance(x, str) and (x.endswith("/adb") or x == "adb")
                for x in value[:i]
            ):
                issues.append(
                    {
                        "field": location + "[" + str(i + 1) + "]",
                        "rule": "adb_device_identifier",
                    }
                )
    return issues


def relative_name(name: str) -> str:
    if not isinstance(name, str) or not name or "\\" in name:
        raise ValueError("引用必须是非空 POSIX 相对路径")
    parts = PurePosixPath(name)
    if parts.is_absolute() or ".." in parts.parts or parts.as_posix() != name:
        raise ValueError("引用越界或不规范")
    return name


class Tree:
    """索引只读暂存字节；工作树拒绝符号链接和目录越界。"""

    def __init__(self, root: Path, tree: str = "worktree"):
        self.root = Path(root).resolve()
        self.tree = tree
        self.index = {}
        if tree == "index":
            data = subprocess.check_output(
                ["git", "-C", str(self.root), "ls-files", "--stage", "-z"]
            )
            for row in data.split(b"\0"):
                if not row:
                    continue
                metadata, name = row.split(b"\t", 1)
                mode, oid, stage = metadata.decode().split()
                if stage != "0" or mode not in ("100644", "100755"):
                    raise ValueError("索引存在冲突或非普通文件")
                self.index[relative_name(name.decode())] = oid
        elif tree != "worktree":
            raise ValueError("未知输入树")

    def path(self, name: str) -> Path:
        name = relative_name(name)
        path = self.root / name
        if not path.resolve().is_relative_to(self.root):
            raise ValueError("引用越过仓库边界")
        parent = path
        while parent != self.root:
            if parent.is_symlink():
                raise ValueError("交付引用不能是符号链接")
            parent = parent.parent
        return path

    def read(self, name: str) -> bytes:
        relative_name(name)
        if self.tree == "index":
            if name not in self.index:
                raise ValueError("引用未进入暂存树：" + name)
            data = subprocess.check_output(
                ["git", "-C", str(self.root), "cat-file", "blob", self.index[name]]
            )
        else:
            data = self.path(name).read_bytes()
        return data

    def names(self, prefixes=()) -> list[str]:
        if self.tree == "index":
            names = self.index
        else:
            raw = subprocess.check_output(
                [
                    "git",
                    "-C",
                    str(self.root),
                    "ls-files",
                    "--cached",
                    "--others",
                    "--exclude-standard",
                    "-z",
                ],
                stderr=subprocess.DEVNULL,
            )
            names = {x.decode() for x in raw.split(b"\0") if x}
            names = {
                n
                for n in names
                if (self.root / n).is_file() or (self.root / n).is_symlink()
            }
        return sorted(
            n
            for n in names
            if not prefixes
            or any(n == p or n.startswith(p.rstrip("/") + "/") for p in prefixes)
        )


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
        "--repo-root", type=Path, default=Path(__file__).resolve().parents[1]
    )
    parser.add_argument("--tree", choices=("worktree", "index"), default="worktree")
    parser.add_argument("--paths", nargs="*", default=[])
    args = parser.parse_args()
    try:
        result = scan(Tree(args.repo_root, args.tree), args.paths)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        result = {"status": "failed", "error": type(error).__name__}
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
