"""P02 本次设备与受控对端结果校验，拒绝只有成功退出码或旧报告的假通过。"""

from __future__ import annotations

import ipaddress
import json
import re
from pathlib import Path

CORE = "7592951b808c4839cb9d77cc4bc642f124915951"
BRIDGE_CLASS = "com.github.zzwtsy.chuanmen.bridge.NativeBridgeContractTest"
DATAPATH_CLASS = "com.github.zzwtsy.chuanmen.vpn.LiveDatapathTest"
RESOURCE_CLASS = "com.github.zzwtsy.chuanmen.vpn.LiveResourceRegressionTest"
LIFECYCLE_CLASS = "com.github.zzwtsy.chuanmen.vpn.LiveLifecycleTest"
DNS_CLASS = "com.github.zzwtsy.chuanmen.vpn.LiveDnsTest"
POLICY_CLASS = "com.github.zzwtsy.chuanmen.vpn.LivePolicyRecoveryTest"
NETWORK_CLASS = "com.github.zzwtsy.chuanmen.vpn.LiveNetworkPolicyTest"
REVOKE_CLASS = "com.github.zzwtsy.chuanmen.vpn.LiveSystemRevokeTest"
CASES = {
    "bridge": {
        (BRIDGE_CLASS, name)
        for name in (
            "loadedLibraryHasFormalBuildIdentity",
            "borrowedPipeIsDrainedBeforePfdAndJniRelease",
            "failedProtectionInstallReplacementAndConcurrentStopAreConfirmed",
        )
    },
    "datapath": {(DATAPATH_CLASS, "controlledPeerRoundtripsThroughFormalService")},
    "resources": {(RESOURCE_CLASS, "fiftySessionsReleaseOwnedResourcesAfterWarmup")},
    "lifecycle": {(LIFECYCLE_CLASS, "permissionAndServiceLifecycleAreConfirmed")},
    "dns": {(DNS_CLASS, "controlledResolverQueriesHaveActualPeerAnswers")},
    "policy": {(POLICY_CLASS, "dhcpDefaultRouteAndExplicitRecoveryPreserveLocalEdits")},
    "network": {(NETWORK_CLASS, "physicalNetworkRecoveryAndIpv6BlockingAreObserved")},
    "revoke": {(REVOKE_CLASS, "systemForgetRevokesActiveServiceAndDrainsResources")},
}
SHA256 = re.compile(r"[0-9a-f]{64}")
RUN_ID = re.compile(r"p02-[A-Za-z0-9-]{1,80}")


def instrumentation_results(output: str, expected: set[tuple[str, str]]) -> dict:
    """同时核对每项开始/通过、总数及正常结束；初始化失败或遗漏也必须失败。"""
    started: set[tuple[str, str]] = set()
    passed: set[tuple[str, str]] = set()
    fields: dict[str, str] = {}
    terminal = 0
    for line in output.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, _, value = line.removeprefix("INSTRUMENTATION_STATUS: ").partition("=")
            fields[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            code = int(line.rsplit(":", 1)[1])
            if "class" in fields or "test" in fields:
                identity = (fields["class"], fields["test"])
                if identity not in expected or int(fields["numtests"]) != len(expected):
                    raise ValueError("设备用例身份或总数不符")
                if code == 1 and identity not in started:
                    started.add(identity)
                elif code == 0 and identity in started and identity not in passed:
                    passed.add(identity)
                else:
                    raise ValueError("用例失败、跳过、重复或没有开始记录")
            elif code != 0:
                raise ValueError("没有身份的设备错误")
            fields = {}
        elif line.startswith("INSTRUMENTATION_CODE: "):
            if line != "INSTRUMENTATION_CODE: -1":
                raise ValueError("设备执行未正常结束")
            terminal += 1
        elif line.startswith(("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:")):
            raise ValueError("设备执行中断或失败")
    if started != expected or passed != expected or terminal != 1 or fields:
        raise ValueError("本次设备报告缺失、不完整或未结束")
    return {
        "tests": len(passed),
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "cases": [{"class": cls, "name": name} for cls, name in sorted(passed)],
    }


def peer_input(path: Path, run_id: str) -> dict:
    """只接受本次已就绪的固定核心私有对端收据，不从旧设备记录继承通过。"""
    if not RUN_ID.fullmatch(run_id):
        raise ValueError("受控请求身份格式无效")
    peer = json.loads(path.read_text())
    if (
        peer.get("status") not in {"ready", "stopped"}
        or peer.get("run_id") != run_id
        or peer.get("core_commit") != CORE
    ):
        raise ValueError("缺少本次就绪的固定对端")
    if not SHA256.fullmatch(peer.get("binary_sha256", "")) or not SHA256.fullmatch(
        peer.get("runner_sha256", "")
    ):
        raise ValueError("对端产物或运行器身份缺失")
    if (
        peer.get("host_tun") is not False
        or peer.get("system_routes_modified") is not False
    ):
        raise ValueError("对端资源范围不符")
    return peer


def datapath_results(report: dict, echoes: list[dict], run_id: str) -> dict:
    """四个业务请求必须与对端逐项关联；Running、TUN 双向计数与保护结果也逐场景核对。"""
    if report.get("status") != "passed" or report.get("run_id") != run_id:
        raise ValueError("缺少本次正式数据通路成功结果")
    requests = report["requests"]
    expected = {(outer, inner) for outer in ("tcp", "udp") for inner in ("tcp", "udp")}
    if len(requests) != 4 or {(x["outer"], x["inner"]) for x in requests} != expected:
        raise ValueError("缺少双外层双业务协议")
    seen: set[str] = set()
    for request in requests:
        digest = request["request_sha256"]
        if (
            not SHA256.fullmatch(digest)
            or digest in seen
            or digest != request["response_sha256"]
            or request["bytes"] <= 0
        ):
            raise ValueError("业务请求或回包身份无效")
        seen.add(digest)
        matches = [
            echo
            for echo in echoes
            if echo.get("protocol") == request["inner"]
            and echo.get("request_sha256") == digest
            and echo.get("bytes") == request["bytes"]
        ]
        if len(matches) != 1:
            raise ValueError("缺少唯一的本轮对端回显记录")
    scenarios = report["scenarios"]
    if len(scenarios) != 2 or {s["outer"] for s in scenarios} != {"tcp", "udp"}:
        raise ValueError("场景集合不完整")
    for scenario in scenarios:
        if (
            scenario["phase"] != "Running"
            or scenario["protection_calls"] <= 0
            or scenario["protection_failures"] != 0
            or scenario["tx_bytes"] <= 0
            or scenario["rx_bytes"] <= 0
            or scenario["activity_recreation_preserved_session"] is not True
        ):
            raise ValueError("平台、保护、计数或 Activity 会话结果不符")
    if scenarios[1]["generation"] <= scenarios[0]["generation"]:
        raise ValueError("核心重建未递增代次")
    return {
        "matched_roundtrips": len(requests),
        "scenarios": scenarios,
        "requests": requests,
    }


def resource_results(report: dict, echoes: list[dict], run_id: str) -> dict:
    """保留并核对 4 轮预热和全部 50 轮资源样本，固定基线不能随执行上移。"""
    if (
        report.get("status") != "passed"
        or report.get("run_id") != run_id
        or report.get("warmup_cycles") != 4
        or report.get("required_cycles") != 50
    ):
        raise ValueError("缺少本轮完整的 50 轮资源结果")
    cycles = report["cycles"]
    requests = report["requests"]
    if len(cycles) != 54 or len(requests) != 108:
        raise ValueError("预热、资源轮次或业务请求不完整")
    baseline = cycles[3]
    if (
        report["baseline_fds"] != baseline["fd_count"]
        or report["baseline_shared_runtime_workers"]
        != baseline["shared_runtime_workers"]
        or baseline["shared_runtime_workers"] < 2
    ):
        raise ValueError("共享 runtime 或 fd 基线无效")
    previous_generation = 0
    for index, cycle in enumerate(cycles):
        if (
            cycle["index"] != index
            or cycle["warmup"] is not (index < 4)
            or cycle["generation"] <= previous_generation
        ):
            raise ValueError("资源样本次序、预热或会话代次无效")
        previous_generation = cycle["generation"]
        if cycle["phase_after_stop"] != "Idle" or any(
            cycle[key] != 0
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
            )
        ):
            raise ValueError("停止后仍有资源、旧借用或保护失败")
        if (
            cycle["service_jobs"] != 1
            or cycle["service_lifecycle_subscribers"] != 1
            or cycle["protection_calls"] <= 0
            or cycle["tx_bytes"] <= 0
            or cycle["rx_bytes"] <= 0
            or not 0 <= cycle["peer_settle_millis"] <= 45_000
        ):
            raise ValueError("实际订阅、任务或数据面事实无效")
        if index >= 4 and (
            cycle["fd_count"] > baseline["fd_count"] + 2
            or cycle["shared_runtime_workers"] != baseline["shared_runtime_workers"]
        ):
            raise ValueError("资源超过固定预热基线")
        expected_outer = "tcp" if index % 2 == 0 else "udp"
        related = [r for r in requests if r["cycle"] == index]
        if (
            cycle["outer"] != expected_outer
            or len(related) != 2
            or {r["inner"] for r in related} != {"tcp", "udp"}
            or any(r["outer"] != expected_outer for r in related)
        ):
            raise ValueError("资源轮次缺少双业务回包")
    first = sorted(c["fd_count"] for c in cycles[4:14])[5]
    last = sorted(c["fd_count"] for c in cycles[-10:])[5]
    if last > first + 1:
        raise ValueError("fd 样本仍呈持续增长")
    seen: set[str] = set()
    for request in requests:
        digest = request["request_sha256"]
        if (
            not SHA256.fullmatch(digest)
            or digest in seen
            or digest != request["response_sha256"]
            or request["bytes"] <= 0
        ):
            raise ValueError("资源回归业务请求身份无效")
        seen.add(digest)
        matching = [
            echo
            for echo in echoes
            if echo.get("protocol") == request["inner"]
            and echo.get("request_sha256") == digest
            and echo.get("bytes") == request["bytes"]
        ]
        if len(matching) != 1:
            raise ValueError("资源回归缺少本轮唯一对端关联")
    return {
        "warmup_cycles": 4,
        "verified_cycles": 50,
        "matched_roundtrips": len(requests),
        "baseline_fds": report["baseline_fds"],
        "baseline_shared_runtime_workers": report["baseline_shared_runtime_workers"],
        "cycles": cycles,
        "requests": requests,
    }


def dns_results(report: dict, responses: list[dict], run_id: str) -> dict:
    """正常 resolver 的双外层查询须与实际 53 端口事务对应，配置读回不能代替回答。"""
    queries = report.get("queries", [])
    if (
        report.get("status") != "passed"
        or report.get("run_id") != run_id
        or len(queries) != 2
    ):
        raise ValueError("缺少本轮完整 DNS 查询")
    if {query["outer"] for query in queries} != {"tcp", "udp"}:
        raise ValueError("DNS 缺少双外层")
    seen = set()
    for query in queries:
        digest = query["query_name_sha256"]
        if not SHA256.fullmatch(digest) or digest in seen:
            raise ValueError("DNS 请求身份缺失或重复")
        seen.add(digest)
        if (
            query.get("vpn_network") is not True
            or query["answers"] != ["10.222.0.1"]
            or query["dns_servers"] != ["10.222.0.1"]
            or query["actual_ipv4"] != "10.222.0.2"
            or query["generation"] < 1
            or query["tx_bytes"] <= 0
            or query["rx_bytes"] <= 0
            or query["protection_failures"] != 0
        ):
            raise ValueError("系统 VPN DNS、答案或实际 I/O 事实不符")
        related = [
            answer
            for answer in responses
            if answer.get("query_name_sha256") == digest
            and answer.get("query_type") == 1
        ]
        if not related or any(
            answer.get("answer_ipv4") != "10.222.0.1"
            or not 0 <= answer["transaction"] <= 65535
            or answer["query_bytes"] <= 0
            or answer["response_bytes"] <= answer["query_bytes"]
            for answer in related
        ):
            raise ValueError("DNS 缺少本轮真实事务和 A 回答")
    return {"queries": queries, "peer_responses": responses}


def policy_results(report: dict, echoes: list[dict], run_id: str) -> dict:
    """DHCP、默认路由与恢复都须有本轮回包；失败不能覆盖保存、名称和草稿。"""
    if report.get("status") != "passed" or report.get("run_id") != run_id:
        raise ValueError("缺少本轮策略与恢复结果")
    steps = report["steps"]
    if [step["step"] for step in steps] != [
        "dhcp",
        "default-route",
        "failed-apply",
        "restore",
    ]:
        raise ValueError("策略与恢复步骤不完整")
    dhcp, routed, failed, restored = steps
    address = ipaddress.IPv4Address(dhcp["actual_ipv4"])
    if address not in ipaddress.IPv4Network("10.222.0.0/24") or str(address) in {
        "10.222.0.0",
        "10.222.0.1",
        "10.222.0.255",
    }:
        raise ValueError("DHCP 实际地址不符")
    if (
        dhcp["generation"] < 1
        or routed["generation"] <= dhcp["generation"]
        or routed["revision"] <= dhcp["revision"]
        or routed["mtu"] != 1400
        or routed["platform_default_route"] is not True
        or routed["protection_failures"] != 0
        or failed["actual_phase"] not in {"Running", "Failed"}
        or failed["failure_reason"]
        not in {
            "InvalidConfig",
            "TunInstallFailed",
            "AddressUnavailable",
            "EngineRejected",
        }
        or failed["historical_revision"] != routed["revision"]
        or failed["saved_revision"] <= routed["revision"]
        or restored["running_revision"] != routed["revision"]
        or restored["saved_revision"] != failed["saved_revision"]
        or restored["generation"] < routed["generation"]
        or (
            failed["actual_phase"] == "Failed"
            and restored["generation"] <= routed["generation"]
        )
        or any(
            restored[key] is not True
            for key in ("saved_preserved", "name_preserved", "draft_preserved")
        )
    ):
        raise ValueError("实际路由、失败或恢复边界不符")
    requests = report["requests"]
    expected = {
        (step, inner)
        for step in ("dhcp", "default-route", "restore")
        for inner in ("tcp", "udp")
    }
    if len(requests) != 6 or {(r["step"], r["inner"]) for r in requests} != expected:
        raise ValueError("策略场景缺少双业务请求")
    seen = set()
    for request in requests:
        digest = request["request_sha256"]
        if (
            not SHA256.fullmatch(digest)
            or digest in seen
            or request["response_sha256"] != digest
            or request["bytes"] <= 0
        ):
            raise ValueError("策略请求身份或回答无效")
        seen.add(digest)
        matches = [
            echo
            for echo in echoes
            if echo.get("protocol") == request["inner"]
            and echo.get("request_sha256") == digest
            and echo.get("bytes") == request["bytes"]
        ]
        if len(matches) != 1:
            raise ValueError("策略请求缺少唯一实际对端回包")
    return {"steps": steps, "requests": requests, "matched_roundtrips": 6}


def network_results(report: dict, echoes: list[dict], run_id: str) -> dict:
    """IPv6 前后正向回包与 VPN 内阻断成对核对；真实切网须更新会话且手动停止不复活。"""
    if report.get("status") != "passed" or report.get("run_id") != run_id:
        raise ValueError("缺少本轮物理网络与 IPv6 结果")
    if (
        report["initial_generation"] < 1
        or report["recovered_generation"] <= report["initial_generation"]
        or report["physical_wifi_identity_changed"] is not True
        or report["manual_stop_survives_wifi_recovery"] is not True
        or any(
            report[key] != 0
            for key in (
                "protection_failures",
                "active_sessions_after_stop",
                "owned_pfds_after_stop",
            )
        )
    ):
        raise ValueError("物理网络恢复或手动停止结果不符")
    requests = report["requests"]
    expected = {
        (stage, f"ipv6-{protocol}")
        for stage in ("before-vpn", "after-vpn")
        for protocol in ("tcp", "udp")
    } | {
        (stage, protocol)
        for stage in ("initial", "recovered")
        for protocol in ("tcp", "udp")
    }
    if (
        len(requests) != 8
        or {(r["stage"], r["protocol"]) for r in requests} != expected
    ):
        raise ValueError("IPv6 正向基线或恢复业务请求缺失")
    seen = set()
    for request in requests:
        digest = request["request_sha256"]
        if (
            not SHA256.fullmatch(digest)
            or digest in seen
            or request["response_sha256"] != digest
            or request["bytes"] <= 0
        ):
            raise ValueError("物理网络请求身份或回答无效")
        seen.add(digest)
        matches = [
            echo
            for echo in echoes
            if echo.get("protocol") == request["protocol"]
            and echo.get("request_sha256") == digest
            and echo.get("bytes") == request["bytes"]
        ]
        if len(matches) != 1:
            raise ValueError("缺少本次唯一物理网络回包")
    blocked = report["blocked_requests"]
    if len(blocked) != 2 or {r["protocol"] for r in blocked} != {
        "ipv6-tcp",
        "ipv6-udp",
    }:
        raise ValueError("IPv6 阻断场景缺失")
    for request in blocked:
        digest = request["request_sha256"]
        if (
            not SHA256.fullmatch(digest)
            or digest in seen
            or request["blocked"] is not True
            or any(echo.get("request_sha256") == digest for echo in echoes)
        ):
            raise ValueError("IPv6 在 VPN 期间逃逸或阻断身份无效")
        seen.add(digest)
    return {"matched_roundtrips": 8, **report}


def revoke_results(report: dict) -> dict:
    """系统 Forget 必须撤销实际授权、通知和资源；直接调用生命周期不能替代此记录。"""
    if (
        report.get("status") != "passed"
        or report.get("scope") != "api37_owned_emulator_system_forget"
    ):
        raise ValueError("缺少本次系统撤销结果")
    if any(
        report.get(key) is not True
        for key in (
            "running_before_revoke",
            "system_permission_revoked",
            "service_revoked_confirmed",
            "foreground_notification_removed",
            "no_automatic_restart",
        )
    ) or any(
        report.get(key) != 0
        for key in ("active_sessions_after_revoke", "owned_pfds_after_revoke")
    ):
        raise ValueError("系统撤销后的授权、通知或资源未收束")
    return report


def lifecycle_results(report: dict) -> dict:
    """正常系统授权和 Service 实际确认均需完整；该结果不承担对端业务连通判据。"""
    if (
        report.get("status") != "passed"
        or report.get("scope") != "platform_lifecycle_without_peer"
    ):
        raise ValueError("缺少本轮平台生命周期成功结果")
    if any(
        report.get(key) is not True
        for key in (
            "denial_without_core",
            "cancel_ignores_late_grant",
            "running_confirmed",
            "activity_recreation_preserved_session",
            "manual_stop_remains_idle",
        )
    ) or any(
        report.get(key) != 0
        for key in ("active_sessions_after_stop", "owned_pfds_after_stop")
    ):
        raise ValueError("授权、取消、重建或停止确认缺失")
    if report.get("actual_ipv4") != "10.222.0.2" or report.get("generation", 0) < 1:
        raise ValueError("实际地址或会话身份缺失")
    return report
