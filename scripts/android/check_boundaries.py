"""检查源码 import、切片及 demo/live 边界；模块依赖由 Gradle 模型检查。"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

PACKAGE = "com.github.zzwtsy.chuanmen"


def violations(root: Path) -> list[str]:
    """检查源码直接引用，不解析构建脚本或替代编译、运行验证。"""
    errors: list[str] = []
    for module in ("contracts", "app", "core-bridge", "vpn"):
        source = root / module / "src"
        for path in sorted(source.rglob("*")):
            if path.suffix not in {".kt", ".java"}:
                continue
            relative = path.relative_to(root).as_posix()
            text = path.read_text()
            imports = re.findall(r"^import\s+([^\s;]+)", text, re.MULTILINE)
            source_set = path.relative_to(root / module / "src").parts[0]
            production = source_set in {"main", "live", "liveDebug", "liveRelease"}
            if production and module in {"core-bridge", "vpn"}:
                forbidden = [
                    item
                    for item in imports
                    if item.startswith(PACKAGE + ".")
                    and not item.startswith(
                        (
                            PACKAGE + ".contracts.",
                            PACKAGE + ".bridge.",
                            PACKAGE + ".vpn.",
                        )
                    )
                ]
                if forbidden:
                    errors.append(f"{relative}: 服务/桥不能反向依赖应用 ({forbidden})")
            if (
                production
                and module == "core-bridge"
                and any(
                    item.startswith(("android.", "androidx.", PACKAGE + ".vpn."))
                    for item in imports
                )
            ):
                errors.append(f"{relative}: 桥不能依赖平台或 Service")
            if (
                production
                and module == "app"
                and (
                    any(item.startswith(PACKAGE + ".bridge.") for item in imports)
                    or re.search(r"System\.loadLibrary|\bexternal\s+fun\b", text)
                )
            ):
                errors.append(f"{relative}: 应用只能通过服务客户端访问真实核心")
            if module == "contracts":
                forbidden = [
                    item
                    for item in imports
                    if item.startswith(("android.", "androidx."))
                    or (
                        item.startswith(PACKAGE + ".")
                        and not item.startswith(PACKAGE + ".contracts.")
                    )
                ]
                if forbidden or re.search(
                    r"\bexternal\s+fun\b|System\.loadLibrary|\bandroidx?\.", text
                ):
                    errors.append(
                        f"{relative}: 纯 Kotlin 契约混入 Android、JNI 或业务实现"
                    )
            if re.match(r"app/src/(?:main|live[^/]*)/", relative) and re.search(
                r"\bFakeEngine\b|\bDemoProfileRepository\b|\bDemoSessionController\b|application\.demo\.",
                text,
            ):
                errors.append(f"{relative}: 正式/公共源码不能引用 demo 实现")
            feature = re.search(r"/feature/([^/]+)/", relative)
            if feature:
                owner = feature.group(1)
                test_source = (
                    path.relative_to(root / module / "src")
                    .parts[0]
                    .startswith(("test", "androidTest"))
                )
                for item in imports:
                    target = re.match(re.escape(PACKAGE) + r"\.feature\.([^\.]+)", item)
                    fixture_import = test_source and item.startswith(
                        PACKAGE + ".application.demo."
                    )
                    if (
                        item.startswith(PACKAGE + ".application.")
                        and not fixture_import
                    ) or (target and target.group(1) != owner):
                        errors.append(
                            f"{relative}: 切片不能依赖装配层或其他切片的私有类型 ({item})"
                        )
                if path.name.endswith("Screen.kt") and re.search(
                    r"\b(CoreEngine|EngineCommand|EngineEvent|ProfileRepository|RoomDatabase)\b|\.storage\.|\.codec\.|System\.loadLibrary",
                    text,
                ):
                    errors.append(f"{relative}: 页面不能直接访问引擎、仓库实现或 JNI")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root", type=Path, default=Path(__file__).resolve().parents[2]
    )
    args = parser.parse_args()
    errors = violations(args.root)
    for error in errors:
        print(error)
    if errors:
        print(f"BOUNDARY_FAILED: {len(errors)} 项")
        return 1
    print(
        "BOUNDARY_OK: 源码 import、切片、页面和 demo/live 边界通过；模块依赖另由 Gradle 检查"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
