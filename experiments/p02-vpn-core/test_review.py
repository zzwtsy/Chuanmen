"""退出记录复核的负向回归，夹具不执行 Android 或 VPN。"""

import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import review


class RecordReviewTest(unittest.TestCase):
    def test_reports_require_exact_sources_and_actual_completed_cases(self):
        """精确源码和实际 JNI 用例完整才通过；快照、主程序、回执或被测 APK 被替换均拒绝。"""
        for change in ("none", "snapshot", "current", "receipt", "apk", "result"):
            with (
                self.subTest(change=change),
                tempfile.TemporaryDirectory() as directory,
            ):
                root = Path(directory)
                base = root / "run"
                device = base / "device"
                device.mkdir(parents=True)
                name = "app/src/main/Main.kt"
                current = root / name
                current.parent.mkdir(parents=True)
                current.write_text("synthetic-source")
                snapshot = device / "source-snapshot" / name
                snapshot.parent.mkdir(parents=True)
                snapshot.write_bytes(current.read_bytes())
                sources = {"files": {name: review.sha(current)}}
                (device / "source-inputs.json").write_text(json.dumps(sources))
                output = []
                for cls, test in sorted(review.live_contract.CASES["bridge"]):
                    for status in (1, 0):
                        output.extend(
                            [
                                f"INSTRUMENTATION_STATUS: class={cls}",
                                f"INSTRUMENTATION_STATUS: test={test}",
                                "INSTRUMENTATION_STATUS: numtests=3",
                                f"INSTRUMENTATION_STATUS_CODE: {status}",
                            ]
                        )
                output.append("INSTRUMENTATION_CODE: -1")
                text = "\n".join(output)
                (device / "live-instrumentation.log").write_text(text)
                summary = {
                    **review.live_contract.instrumentation_results(
                        text, review.live_contract.CASES["bridge"]
                    ),
                    "scope": "bridge",
                    "mode": "live",
                    "api": 36,
                    "abi": "arm64-v8a",
                    "source_inputs_sha256": review.sha(device / "source-inputs.json"),
                    "artifacts": {
                        "app/build/outputs/apk/live/debug/app-live-debug.apk": "1" * 64
                    },
                }
                result = {"status": "passed"}
                if change == "snapshot":
                    snapshot.write_text("changed")
                if change == "current":
                    current.write_text("changed")
                if change == "receipt":
                    (device / "live-instrumentation.log").write_text(
                        text.replace("STATUS_CODE: 0", "STATUS_CODE: -2")
                    )
                if change == "apk":
                    summary["artifacts"][
                        "app/build/outputs/apk/live/debug/app-live-debug.apk"
                    ] = "2" * 64
                if change == "result":
                    result["status"] = "failed"
                (device / "device-tests.json").write_text(json.dumps(summary))
                (device / "result.json").write_text(json.dumps(result))
                with patch.object(review, "ROOT", root):
                    if change == "none":
                        self.assertEqual(
                            "passed",
                            review.review_case(base, "bridge", "1" * 64)["status"],
                        )
                    else:
                        with self.assertRaises(ValueError):
                            review.review_case(base, "bridge", "1" * 64)


if __name__ == "__main__":
    unittest.main()
