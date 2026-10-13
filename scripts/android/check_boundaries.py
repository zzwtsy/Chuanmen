"""检查 P01 实际模块与 Kotlin import 边界，不替代编译或运行行为测试。"""

from __future__ import annotations

import argparse
import re
from pathlib import Path

PACKAGE = "com.github.zzwtsy.chuanmen"


def violations(root: Path) -> list[str]:
    """按当前模块图检查源码；新增模块必须同时显式调整允许依赖。"""
    errors: list[str] = []
    contracts_build = root / "contracts/build.gradle.kts"
    if not contracts_build.is_file():
        errors.append("contracts/build.gradle.kts: 缺少实际契约模块")
    else:
        content = contracts_build.read_text()
        if re.search(r"project\s*\(|android[.-]|libs\.androidx", content):
            errors.append(
                "contracts/build.gradle.kts: 契约模块不能依赖业务模块或 Android"
            )
        dependencies = re.findall(
            r"^\s*(?:api|implementation|testImplementation)\(([^\n]+)\)",
            content,
            re.MULTILINE,
        )
        if any(
            item not in {"libs.kotlinx.coroutines.core", "libs.junit"}
            for item in dependencies
        ):
            errors.append("contracts/build.gradle.kts: 存在未声明的依赖")
    app_build = root / "app/build.gradle.kts"
    if app_build.is_file():
        content = app_build.read_text()
        modules = re.findall(r'project\("([^"]+)"\)', content)
        if modules != [":contracts"]:
            errors.append("app/build.gradle.kts: 当前只允许依赖实际存在的 :contracts")
        if (
            "dependsOn(verifyLiveEngine)" not in content
            or "LIVE_ENGINE_MISSING" not in content
        ):
            errors.append("app/build.gradle.kts: 缺少 live 构建阻断")

    for module in ("contracts", "app"):
        source = root / module / "src"
        for path in sorted(source.rglob("*")):
            if path.suffix not in {".kt", ".java"}:
                continue
            relative = path.relative_to(root).as_posix()
            text = path.read_text()
            imports = re.findall(r"^import\s+([^\s;]+)", text, re.MULTILINE)
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
            if relative.startswith(("app/src/main/", "app/src/live/")) and re.search(
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
    print("BOUNDARY_OK: :app -> :contracts；切片、页面和 demo/live 源码边界通过")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
