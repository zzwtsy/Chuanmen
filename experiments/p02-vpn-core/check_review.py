"""独立核对 P02 补丁评审身份；用于阶段 CI 和退出审阅，不参与普通 live 构建。"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def verify(root: Path = ROOT) -> dict:
    """评审必须对应当前来源、协议与补丁；旧记录或缺失记录不能通过阶段检查。"""
    path = root / "native/inputs.json"
    inputs = json.loads(path.read_bytes())
    review = json.loads(
        (root / "docs/verification/p02/native-review.json").read_bytes()
    )
    expected = {
        "schema_version": 1,
        "status": "reviewed",
        "scope": "p02_controlled_single_process_single_vpn",
        "protocol": inputs["protocol"],
        "core_commit": inputs["core_commit"],
        "patch_id": inputs["patch_id"],
        "native_inputs_sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        "patch_sha256": inputs["files"]["native/patches/session-host.diff"],
        "public_release_approval": False,
    }
    if any(review.get(key) != value for key, value in expected.items()):
        raise ValueError("正式补丁评审缺失、范围不符或来源已经变化")
    report = root / "docs/verification/p02/native-review.md"
    if (
        not report.is_file()
        or not review.get("reviewer_role")
        or review.get("review_items")
        != [
            "mandatory_protector",
            "asynchronous_tun_confirmation",
            "stop_drains_borrows_and_callbacks",
            "separate_jni_release",
            "session_and_attachment_identity",
            "bounded_redacted_diagnostics",
        ]
    ):
        raise ValueError("正式补丁评审范围或说明不完整")
    return review


def main() -> int:
    try:
        verify()
        print("NATIVE_REVIEW_OK: 当前正式补丁工程评审；不代表公开发行批准")
        return 0
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"NATIVE_REVIEW_INVALID: {error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
