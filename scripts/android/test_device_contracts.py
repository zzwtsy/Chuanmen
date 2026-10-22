"""本次正式设备结果与对端关联的正负回归，不连接设备。"""

import copy
import unittest

from device_contracts import (
    CASES,
    datapath_results,
    dns_results,
    instrumentation_results,
    lifecycle_results,
    network_results,
    policy_results,
    resource_results,
    revoke_results,
)


class LiveContractTest(unittest.TestCase):
    def test_system_revoke_requires_permission_notification_and_drain(self):
        """真实撤销记录完整才通过；缺授权撤销、通知移除或仍有借用均拒绝。"""
        original = {
            "status": "passed",
            "scope": "api37_owned_emulator_system_forget",
            "running_before_revoke": True,
            "system_permission_revoked": True,
            "service_revoked_confirmed": True,
            "foreground_notification_removed": True,
            "no_automatic_restart": True,
            "active_sessions_after_revoke": 0,
            "owned_pfds_after_revoke": 0,
        }
        self.assertEqual(original, revoke_results(original))
        for key in (
            "system_permission_revoked",
            "service_revoked_confirmed",
            "foreground_notification_removed",
            "no_automatic_restart",
            "active_sessions_after_revoke",
            "owned_pfds_after_revoke",
        ):
            report = {**original, key: False if isinstance(original[key], bool) else 1}
            with self.subTest(key=key), self.assertRaises(ValueError):
                revoke_results(report)

    def test_network_requires_ipv6_positive_baseline_and_no_vpn_escape(self):
        """物理 IPv6 前后回答且 VPN 内零逃逸才通过；缺基线、伪恢复或对端收到阻断请求均失败。"""
        requests, echoes = [], []
        pairs = [
            (s, f"ipv6-{p}")
            for s in ("before-vpn", "after-vpn")
            for p in ("tcp", "udp")
        ] + [(s, p) for s in ("initial", "recovered") for p in ("tcp", "udp")]
        for index, (stage, protocol) in enumerate(pairs):
            digest = f"{index + 1:064x}"
            requests.append(
                {
                    "stage": stage,
                    "protocol": protocol,
                    "bytes": 40,
                    "request_sha256": digest,
                    "response_sha256": digest,
                }
            )
            echoes.append({"protocol": protocol, "bytes": 40, "request_sha256": digest})
        blocked = [
            {
                "protocol": f"ipv6-{protocol}",
                "blocked": True,
                "request_sha256": f"{index + 100:064x}",
            }
            for index, protocol in enumerate(("tcp", "udp"))
        ]
        original = {
            "status": "passed",
            "run_id": "p02-network",
            "initial_generation": 1,
            "recovered_generation": 2,
            "physical_wifi_identity_changed": True,
            "manual_stop_survives_wifi_recovery": True,
            "protection_failures": 0,
            "active_sessions_after_stop": 0,
            "owned_pfds_after_stop": 0,
            "requests": requests,
            "blocked_requests": blocked,
        }
        self.assertEqual(
            8, network_results(original, echoes, "p02-network")["matched_roundtrips"]
        )
        for key, value in (
            ("recovered_generation", 1),
            ("physical_wifi_identity_changed", False),
            ("manual_stop_survives_wifi_recovery", False),
            ("owned_pfds_after_stop", 1),
            ("requests", requests[:-1]),
        ):
            report = copy.deepcopy(original)
            report[key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                network_results(report, echoes, "p02-network")
        with self.assertRaises(ValueError):
            network_results(original, echoes + [blocked[0]], "p02-network")
        with self.assertRaises(ValueError):
            network_results(original, echoes[:-1], "p02-network")

    def test_policy_requires_actual_requests_and_preserved_revisions(self):
        """DHCP、默认路由与显式恢复完整才通过；缺回包、伪路由或覆盖保存与草稿均拒绝。"""
        steps = [
            {
                "step": "dhcp",
                "actual_ipv4": "10.222.0.2",
                "generation": 1,
                "revision": 1,
            },
            {
                "step": "default-route",
                "generation": 2,
                "revision": 2,
                "mtu": 1400,
                "platform_default_route": True,
                "protection_failures": 0,
            },
            {
                "step": "failed-apply",
                "actual_phase": "Failed",
                "failure_reason": "TunInstallFailed",
                "historical_revision": 2,
                "saved_revision": 3,
            },
            {
                "step": "restore",
                "generation": 4,
                "running_revision": 2,
                "saved_revision": 3,
                "saved_preserved": True,
                "name_preserved": True,
                "draft_preserved": True,
            },
        ]
        requests, echoes = [], []
        for index, (step, inner) in enumerate(
            (s, i) for s in ("dhcp", "default-route", "restore") for i in ("tcp", "udp")
        ):
            digest = f"{index + 1:064x}"
            requests.append(
                {
                    "step": step,
                    "inner": inner,
                    "request_sha256": digest,
                    "response_sha256": digest,
                    "bytes": 30,
                }
            )
            echoes.append({"protocol": inner, "request_sha256": digest, "bytes": 30})
        original = {
            "status": "passed",
            "run_id": "p02-policy",
            "steps": steps,
            "requests": requests,
        }
        self.assertEqual(
            6, policy_results(original, echoes, "p02-policy")["matched_roundtrips"]
        )
        for index, key, value in (
            (0, "actual_ipv4", "0.0.0.0"),
            (1, "platform_default_route", False),
            (1, "protection_failures", 1),
            (2, "actual_phase", "OutcomeUnknown"),
            (3, "saved_revision", 2),
            (3, "draft_preserved", False),
            (3, "generation", 2),
        ):
            report = copy.deepcopy(original)
            report["steps"][index][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                policy_results(report, echoes, "p02-policy")
        with self.assertRaises(ValueError):
            policy_results(original, echoes[:-1], "p02-policy")

    def test_dns_requires_actual_resolver_answer_and_peer_transaction(self):
        """双外层系统回答和对端事务齐全才通过；缺回包、伪地址、保护失败或重复域名均拒绝。"""
        queries, responses = [], []
        for index, outer in enumerate(("tcp", "udp")):
            digest = f"{index + 1:064x}"
            queries.append(
                {
                    "outer": outer,
                    "query_name_sha256": digest,
                    "vpn_network": True,
                    "answers": ["10.222.0.1"],
                    "dns_servers": ["10.222.0.1"],
                    "actual_ipv4": "10.222.0.2",
                    "generation": index + 1,
                    "tx_bytes": 100,
                    "rx_bytes": 100,
                    "protection_failures": 0,
                }
            )
            responses.append(
                {
                    "query_name_sha256": digest,
                    "query_type": 1,
                    "transaction": 256,
                    "query_bytes": 55,
                    "response_bytes": 71,
                    "answer_ipv4": "10.222.0.1",
                }
            )
        original = {"run_id": "p02-dns", "status": "passed", "queries": queries}
        self.assertEqual(
            dns_results(original, responses, "p02-dns")["queries"], queries
        )
        for key, value in {
            "vpn_network": False,
            "answers": [],
            "dns_servers": [],
            "actual_ipv4": "10.222.0.9",
            "generation": 0,
            "tx_bytes": 0,
            "rx_bytes": 0,
            "protection_failures": 1,
            "query_name_sha256": queries[0]["query_name_sha256"],
        }.items():
            report = copy.deepcopy(original)
            report["queries"][1][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError):
                dns_results(report, responses, "p02-dns")
        with self.assertRaises(ValueError):
            dns_results(original, responses[:1], "p02-dns")
        with self.assertRaises(ValueError):
            dns_results(
                original,
                [{**response, "transaction": 65536} for response in responses],
                "p02-dns",
            )

    def output(self, case="bridge"):
        lines = []
        expected = CASES[case]
        for cls, name in sorted(expected):
            for code in (1, 0):
                lines.extend(
                    [
                        f"INSTRUMENTATION_STATUS: class={cls}",
                        f"INSTRUMENTATION_STATUS: test={name}",
                        f"INSTRUMENTATION_STATUS: numtests={len(expected)}",
                        f"INSTRUMENTATION_STATUS_CODE: {code}",
                    ]
                )
        return "\n".join([*lines, "INSTRUMENTATION_CODE: -1"])

    def test_actual_started_and_completed_case_set_is_required(self):
        """两个 JNI 用例及单个数据通路用例的实际开始、通过与结束都完整时分别通过。"""
        for case in CASES:
            with self.subTest(case=case):
                self.assertEqual(
                    len(CASES[case]),
                    instrumentation_results(self.output(case), CASES[case])["tests"],
                )

    def test_success_exit_without_cases_cannot_pass(self):
        """Gradle 成功、安装拒绝、缺结束或 JUnit 失败/跳过/初始化错误均不能登记通过。"""
        valid = self.output()
        samples = [
            "BUILD SUCCESSFUL",
            "INSTALL_FAILED_USER_RESTRICTED",
            "INSTRUMENTATION_CODE: -1",
            valid.removesuffix("INSTRUMENTATION_CODE: -1"),
            valid.replace(
                "INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -2"
            ),
            valid.replace(
                "INSTRUMENTATION_STATUS_CODE: 0", "INSTRUMENTATION_STATUS_CODE: -3"
            ),
            valid.replace(
                "borrowedPipeIsDrainedBeforePfdAndJniRelease", "initializationError"
            ),
            valid + "\nINSTRUMENTATION_CODE: -1",
            valid.replace("numtests=3", "numtests=1"),
        ]
        for sample in samples:
            with self.subTest(sample=sample), self.assertRaises(ValueError):
                instrumentation_results(sample, CASES["bridge"])

    def fixture(self):
        requests, echoes = [], []
        for index, (outer, inner) in enumerate(
            (o, i) for o in ("tcp", "udp") for i in ("tcp", "udp")
        ):
            digest = f"{index + 1:064x}"
            requests.append(
                {
                    "outer": outer,
                    "inner": inner,
                    "request_sha256": digest,
                    "response_sha256": digest,
                    "bytes": 47,
                }
            )
            echoes.append({"protocol": inner, "request_sha256": digest, "bytes": 47})
        scenarios = [
            {
                "outer": outer,
                "phase": "Running",
                "generation": index + 1,
                "protection_calls": 2,
                "protection_failures": 0,
                "tx_bytes": 100,
                "rx_bytes": 100,
                "activity_recreation_preserved_session": True,
            }
            for index, outer in enumerate(("tcp", "udp"))
        ]
        return {
            "status": "passed",
            "run_id": "p02-synthetic",
            "requests": requests,
            "scenarios": scenarios,
        }, echoes

    def test_roundtrips_require_unique_peer_identity_and_actual_resources(self):
        """合成四个请求可逐项关联时通过；任何身份、协议、回包、资源或重建条件缺失均失败。"""
        original, original_echoes = self.fixture()
        self.assertEqual(
            4,
            datapath_results(original, original_echoes, "p02-synthetic")[
                "matched_roundtrips"
            ],
        )
        for change in (
            "run",
            "status",
            "protocol",
            "response",
            "duplicate",
            "missing_peer",
            "duplicate_peer",
            "counts",
            "protection",
            "generation",
            "activity",
        ):
            with self.subTest(change=change):
                report, echoes = copy.deepcopy(original), copy.deepcopy(original_echoes)
                if change == "run":
                    report["run_id"] = "p02-old"
                elif change == "status":
                    report["status"] = "failed"
                elif change == "protocol":
                    report["requests"][0]["inner"] = "udp"
                elif change == "response":
                    report["requests"][0]["response_sha256"] = "0" * 64
                elif change == "duplicate":
                    report["requests"][1] = report["requests"][0]
                elif change == "missing_peer":
                    echoes.pop()
                elif change == "duplicate_peer":
                    echoes.append(echoes[0])
                elif change == "counts":
                    report["scenarios"][0]["rx_bytes"] = 0
                elif change == "protection":
                    report["scenarios"][0]["protection_failures"] = 1
                elif change == "generation":
                    report["scenarios"][1]["generation"] = 1
                elif change == "activity":
                    report["scenarios"][0]["activity_recreation_preserved_session"] = (
                        False
                    )
                with self.assertRaises(ValueError):
                    datapath_results(report, echoes, "p02-synthetic")

    def resource_fixture(self):
        cycles, requests, echoes = [], [], []
        for index in range(54):
            outer = "tcp" if index % 2 == 0 else "udp"
            cycles.append(
                {
                    "index": index,
                    "warmup": index < 4,
                    "outer": outer,
                    "generation": index + 1,
                    "fd_count": 100,
                    "shared_runtime_workers": 2,
                    "active_instances": 0,
                    "tun_borrows": 0,
                    "callbacks_in_flight": 0,
                    "jni_global_refs": 0,
                    "owned_pfds": 0,
                    "observer_subscribers": 0,
                    "protection_failures": 0,
                    "peer_connections_after_stop": 0,
                    "peer_routes_after_stop": 0,
                    "peer_settle_millis": 500,
                    "service_jobs": 1,
                    "service_lifecycle_subscribers": 1,
                    "protection_calls": 2,
                    "tx_bytes": 100,
                    "rx_bytes": 100,
                    "phase_after_stop": "Idle",
                }
            )
            for offset, inner in enumerate(("tcp", "udp")):
                digest = f"{index * 2 + offset + 1:064x}"
                requests.append(
                    {
                        "cycle": index,
                        "outer": outer,
                        "inner": inner,
                        "request_sha256": digest,
                        "response_sha256": digest,
                        "bytes": 47,
                    }
                )
                echoes.append(
                    {"protocol": inner, "request_sha256": digest, "bytes": 47}
                )
        return {
            "status": "passed",
            "run_id": "p02-synthetic",
            "warmup_cycles": 4,
            "required_cycles": 50,
            "baseline_fds": 100,
            "baseline_shared_runtime_workers": 2,
            "cycles": cycles,
            "requests": requests,
        }, echoes

    def test_fifty_resource_cycles_require_every_release_and_fixed_baseline(self):
        """完整预热及 50 轮可通过；缺轮、任何资源残留或基线上移都不能算资源验收。"""
        original, echoes = self.resource_fixture()
        result = resource_results(original, echoes, "p02-synthetic")
        self.assertEqual(50, result["verified_cycles"])
        self.assertEqual(108, result["matched_roundtrips"])
        changes = {
            "missing_cycle": lambda r: r["cycles"].pop(),
            "missing_request": lambda r: r["requests"].pop(),
            "changed_baseline": lambda r: r.update(baseline_fds=101),
            "fd_growth": lambda r: r["cycles"][-1].update(fd_count=103),
            "thread_growth": lambda r: r["cycles"][-1].update(shared_runtime_workers=3),
            "old_generation": lambda r: r["cycles"][-1].update(generation=1),
            "stopping": lambda r: r["cycles"][-1].update(phase_after_stop="Stopping"),
            "old_run": lambda r: r.update(run_id="p02-old"),
            "duplicate_request": lambda r: r["requests"].__setitem__(
                1, r["requests"][0]
            ),
        }
        for key in (
            "active_instances",
            "tun_borrows",
            "callbacks_in_flight",
            "jni_global_refs",
            "owned_pfds",
            "observer_subscribers",
            "protection_failures",
            "peer_connections_after_stop",
            "peer_routes_after_stop",
        ):
            changes[key] = lambda r, key=key: r["cycles"][-1].update({key: 1})
        for key in ("service_jobs", "service_lifecycle_subscribers"):
            changes[key] = lambda r, key=key: r["cycles"][-1].update({key: 2})
        for key in ("tx_bytes", "rx_bytes", "protection_calls"):
            changes[key] = lambda r, key=key: r["cycles"][-1].update({key: 0})
        for name, change in changes.items():
            with self.subTest(change=name):
                report = copy.deepcopy(original)
                change(report)
                with self.assertRaises(ValueError):
                    resource_results(report, echoes, "p02-synthetic")

    def test_resource_trend_and_peer_correlation_cannot_be_masked_by_fd_budget(self):
        """单轮仍在 fd 容差内但尾段持续增长，或对端关联缺失/重复时，均拒绝登记。"""
        original, original_echoes = self.resource_fixture()
        report = copy.deepcopy(original)
        for cycle in report["cycles"][-10:]:
            cycle["fd_count"] = 102
        with self.assertRaisesRegex(ValueError, "持续增长"):
            resource_results(report, original_echoes, "p02-synthetic")
        for echoes in (original_echoes[:-1], [*original_echoes, original_echoes[0]]):
            with self.subTest(echo_count=len(echoes)), self.assertRaises(ValueError):
                resource_results(original, echoes, "p02-synthetic")

    def test_platform_lifecycle_requires_normal_authorization_and_actual_stop(self):
        """正常授权、取消、真实安装及停止观察齐全才通过；任何前置或结果缺失均失败。"""
        report = {
            "status": "passed",
            "scope": "platform_lifecycle_without_peer",
            "denial_without_core": True,
            "cancel_ignores_late_grant": True,
            "running_confirmed": True,
            "activity_recreation_preserved_session": True,
            "manual_stop_remains_idle": True,
            "active_sessions_after_stop": 0,
            "owned_pfds_after_stop": 0,
            "actual_ipv4": "10.222.0.2",
            "generation": 1,
        }
        self.assertEqual(report, lifecycle_results(report))
        for key, value in {
            "status": "failed",
            "scope": "ui-preview",
            "denial_without_core": False,
            "cancel_ignores_late_grant": False,
            "running_confirmed": False,
            "activity_recreation_preserved_session": False,
            "manual_stop_remains_idle": False,
            "active_sessions_after_stop": 1,
            "owned_pfds_after_stop": 1,
            "actual_ipv4": "10.222.0.3",
            "generation": 0,
        }.items():
            with self.subTest(key=key), self.assertRaises(ValueError):
                lifecycle_results({**report, key: value})


if __name__ == "__main__":
    unittest.main()
