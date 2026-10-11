"""读取交付树或 Git 索引；路径和摘要不会借用开发机的其他文件。"""

from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path, PurePosixPath


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


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

    def read(self, name: str, expected: str | None = None) -> bytes:
        relative_name(name)
        if self.tree == "index":
            if name not in self.index:
                raise ValueError("引用未进入暂存树：" + name)
            data = subprocess.check_output(
                ["git", "-C", str(self.root), "cat-file", "blob", self.index[name]]
            )
        else:
            data = self.path(name).read_bytes()
        if expected is not None and digest(data) != expected:
            raise ValueError("引用摘要不匹配：" + name)
        return data

    def object(self, name: str):
        return json.loads(self.read(name))

    def names(self, prefixes=()) -> list[str]:
        if self.tree == "index":
            names = self.index
        else:
            try:
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
                names = {n for n in names if (self.root / n).is_file()}
            except subprocess.CalledProcessError:
                names = {
                    p.relative_to(self.root).as_posix()
                    for p in self.root.rglob("*")
                    if p.is_file()
                    and not any(
                        x in p.parts for x in (".git", "__pycache__", ".local", "build")
                    )
                }
        return sorted(
            n
            for n in names
            if not prefixes
            or any(n == p or n.startswith(p.rstrip("/") + "/") for p in prefixes)
        )


def export_index(root: Path, destination: Path):
    tree = Tree(root, "index")
    destination = Path(destination).resolve()
    if destination.is_relative_to(tree.root):
        raise ValueError("索引导出必须位于仓库之外")
    for name in tree.names():
        path = destination / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(tree.read(name))


def write_new(path: Path, data: bytes, root: Path | None = None):
    path = Path(path)
    if root and path.resolve().is_relative_to(Path(root).resolve()):
        raise ValueError("复核输出必须位于交付树外")
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("xb") as stream:
        stream.write(data)


def json_bytes(value) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()
