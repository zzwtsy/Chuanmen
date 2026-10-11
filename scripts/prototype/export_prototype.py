"""从同一份可编辑源重建原型，并把宿主依赖换为随包固定的本地资源。"""

import argparse
import hashlib
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def main() -> int:
    argparse.ArgumentParser(description=__doc__).parse_args()
    source = ROOT / "prototype/source"
    fragment = (source / "config-ux-shell.html").read_text(encoding="utf-8")
    replacements = {
        "/* CATALOGUE_DATA */": json.dumps(
            json.loads(
                (ROOT / "docs/design/config/assistant-catalog.snapshot.json").read_text(
                    encoding="utf-8"
                )
            ),
            ensure_ascii=False,
            separators=(",", ":"),
        ),
        "/* CONFIG_STYLES */": (source / "config-ux-config.css").read_text(
            encoding="utf-8"
        ),
        "/* MONITOR_STYLES */": (source / "focused-monitor.css").read_text(
            encoding="utf-8"
        ),
        "/* CONFIG_MODULE */": (source / "config-ux-config.js").read_text(
            encoding="utf-8"
        ),
        "/* MONITOR_MODULE */": (source / "focused-monitor.js").read_text(
            encoding="utf-8"
        ),
    }
    for marker, value in replacements.items():
        if fragment.count(marker) != 1:
            raise ValueError(f"组装标记缺失或重复：{marker}")
        fragment = fragment.replace(marker, value)
    fragment = fragment.replace("easytier-unified", "easytier-config")
    (source / "chuanmen-android-config.fragment.html").write_text(
        fragment, encoding="utf-8"
    )

    offline = fragment.replace(
        '<script src="https://cdn.jsdelivr.net/npm/d3@7.9.0/dist/d3.min.js"></script>',
        "",
    )
    if re.search(r"<script\b[^>]*\bsrc=[\"\']https?://", offline, re.IGNORECASE):
        raise ValueError("仍存在没有本地化的运行脚本")
    if "window.openai" in offline:
        raise ValueError("存在未替换的宿主交互")
    for name in ("d3-7.9.0.min.js", "lucide-1.17.0.js"):
        if not (ROOT / "prototype/assets" / name).is_file():
            raise FileNotFoundError(f"离线资源缺失：{name}")

    head = """<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<meta name="referrer" content="no-referrer">
<title>Chuanmen 安卓客户端 · 交互原型</title>
<style>
:root{color-scheme:light dark}
html,body{margin:0;min-height:100%;background:light-dark(#f6f5fa,#141419)}
body{box-sizing:border-box;padding:16px;font-family:system-ui,-apple-system,"Segoe UI","Noto Sans SC",sans-serif}
.cursor-interaction{cursor:pointer}
.viz-dotted-background{background-image:radial-gradient(light-dark(#d2cfda,#383540) .8px,transparent .8px);background-size:16px 16px}
@media(max-width:480px){body{padding:8px}}
</style>
<script src="assets/d3-7.9.0.min.js"></script>
<script src="assets/lucide-1.17.0.js"></script>
</head>
<body>
"""
    tail = """
<script>if(globalThis.lucide){lucide.createIcons({attrs:{width:16,height:16}});}</script>
</body>
</html>
"""
    destination = ROOT / "prototype/index.html"
    destination.write_text(head + offline + tail, encoding="utf-8")
    print(
        json.dumps(
            {
                "status": "PASS",
                "output": "prototype/index.html",
                "fragment_sha256": hashlib.sha256(fragment.encode("utf-8")).hexdigest(),
                "offline_resources": 2,
                "scope": "重建和资源完整性；不代表浏览器像素或 Android 真机验收",
            },
            ensure_ascii=False,
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
