"""正式补丁评审门槛的负向回归，不执行设备或批准发行。"""

import copy
import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

import check_review


class ReviewContractTest(unittest.TestCase):
    def test_review_is_bound_to_actual_native_inputs(self):
        """本次评审身份匹配才通过；来源、协议、范围或审查项被替换均拒绝。"""
        original = check_review.verify()
        with TemporaryDirectory() as directory:
            root = Path(directory)
            for name in (
                "native/inputs.json",
                "docs/verification/p02/native-review.md",
            ):
                target = root / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes((check_review.ROOT / name).read_bytes())
            path = root / "docs/verification/p02/native-review.json"
            path.write_text(json.dumps(original))
            self.assertEqual(original, check_review.verify(root))
            for key, value in (
                ("native_inputs_sha256", "0" * 64),
                ("patch_sha256", "0" * 64),
                ("protocol", 999),
                ("status", "pending"),
                ("scope", "all_platforms"),
                ("review_items", []),
                ("reviewer_role", ""),
                ("public_release_approval", True),
            ):
                changed = copy.deepcopy(original)
                changed[key] = value
                path.write_text(json.dumps(changed))
                with self.subTest(key=key), self.assertRaises(ValueError):
                    check_review.verify(root)
            path.unlink()
            with self.assertRaises(FileNotFoundError):
                check_review.verify(root)
            path.write_text(json.dumps(original))
            (root / "docs/verification/p02/native-review.md").unlink()
            with self.assertRaises(ValueError):
                check_review.verify(root)


if __name__ == "__main__":
    unittest.main()
