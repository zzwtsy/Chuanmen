"""受控 DNS 对端报告的身份与资源范围回归，不执行 Cargo 或网络。"""

import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

import build_native
from build_peer import validate_report


class PeerBuildTest(unittest.TestCase):
    def test_only_exact_inputs_binary_and_no_host_network_changes_are_accepted(self):
        """来源、字节和无宿主 TUN 范围齐全才通过；任一身份或范围字段不符均拒绝。"""
        with TemporaryDirectory() as directory:
            binary = Path(directory) / "synthetic-peer"
            binary.write_bytes(b"synthetic-host")
            sources = {"synthetic/input.rs": "0" * 64}
            inputs = {"patch_id": "p02-synthetic"}
            native = "1" * 64
            report = {
                "schema_version": 1,
                "status": "passed",
                "role": "controlled_dns_peer",
                "core_commit": build_native.COMMIT,
                "patch_id": inputs["patch_id"],
                "rustc_commit": build_native.RUSTC_COMMIT,
                "source_files": sources,
                "native_inputs_sha256": native,
                "virtual_dns_port": 53,
                "binary_sha256": build_native.digest(binary),
                "host_tun": False,
                "system_routes_modified": False,
            }
            validate_report(report, sources, inputs, native, binary)
            for key, value in {
                "schema_version": 2,
                "status": "failed",
                "role": "legacy",
                "core_commit": "0" * 40,
                "patch_id": "p00-old",
                "rustc_commit": "0" * 40,
                "source_files": {},
                "native_inputs_sha256": "0" * 64,
                "virtual_dns_port": 5353,
                "binary_sha256": "0" * 64,
                "host_tun": True,
                "system_routes_modified": True,
            }.items():
                with self.subTest(key=key), self.assertRaises(ValueError):
                    validate_report(
                        {**report, key: value}, sources, inputs, native, binary
                    )
            binary.write_bytes(b"changed-host")
            with self.assertRaises(ValueError):
                validate_report(report, sources, inputs, native, binary)


if __name__ == "__main__":
    unittest.main()
