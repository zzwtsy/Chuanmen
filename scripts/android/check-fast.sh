#!/usr/bin/env bash
set -eu
command -v python3 >/dev/null || { echo "TOOL_MISSING: 需要 Python 3.10+"; exit 3; }
script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
exec python3 "$script_dir/android_tools.py" check-fast "$@"
