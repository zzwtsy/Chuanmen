package com.github.zzwtsy.chuanmen.features.devices

import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.vpn.ConnectionPhase
import com.github.zzwtsy.chuanmen.lib.vpn.CorePeer
import com.github.zzwtsy.chuanmen.lib.vpn.CoreRoute
import com.github.zzwtsy.chuanmen.lib.vpn.CoreSnapshot
import com.github.zzwtsy.chuanmen.lib.vpn.DataFreshness
import com.github.zzwtsy.chuanmen.lib.vpn.Ipv4Cidr
import com.github.zzwtsy.chuanmen.lib.vpn.Observation
import com.github.zzwtsy.chuanmen.lib.vpn.OperationToken
import com.github.zzwtsy.chuanmen.lib.vpn.RuntimeStatus
import com.github.zzwtsy.chuanmen.lib.vpn.SessionId
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import com.github.zzwtsy.chuanmen.testAppliedSnapshot
import com.github.zzwtsy.chuanmen.testSavedRevision
import org.junit.Assert.*
import org.junit.Test

class DevicesViewModelTest {
    private val saved = testSavedRevision()
    private val applied = testAppliedSnapshot(saved)
    private fun snapshot(id: SessionId = applied.sessionId) = CoreSnapshot(
        sessionId = id, lastOperation = OperationToken(id, 1), savedRevision = 1,
        status = RuntimeStatus.Running, ipv4 = Ipv4Cidr("10.42.0.1", 24),
        peers = listOf(CorePeer(3, 0), CorePeer(2, 1)),
        routes = listOf(CoreRoute(3, Ipv4Cidr("10.42.0.3", 32), emptyList(), 3, "offline"),
            CoreRoute(2, Ipv4Cidr("10.42.0.2", 32), listOf(Ipv4Cidr("192.0.2.0", 24)), 8, "2.7.0")),
        udpNatType = 123, tcpNatType = null, attachmentId = null, attachmentState = null,
        counters = null, activeInstances = 1, callbacksInFlight = 0, jniGlobalRefs = 1,
        protectionCalls = 2, protectionFailures = 0,
    )
    private fun session() = SessionState(ConnectionPhase.Running, applied.sessionId, saved.profileId, applied,
        core = Observation(snapshot(), 1000, DataFreshness.Fresh))

    /** 同代 Fresh 观测只生成在线 peer；按 peerId 关联路由，未知 NAT 编号与缺失版本如实显示。 */
    @Test fun onlinePeers_useMatchingRoutesAndExplicitUnknownValues() {
        val state = DevicesViewModel.project(session(), saved.profileId)
        assertNull(state.notice)
        assertEquals("未知类型 (123)", state.local!!.udpNat)
        assertEquals("未知 / 尚未探测", state.local.tcpNat)
        assertEquals("未报告", state.local.version)
        val peer = state.peers.single()
        assertEquals("设备 2", peer.title)
        assertEquals("10.42.0.2", peer.ip)
        assertEquals("2.7.0", peer.version)
        assertEquals("8", peer.routeCost)
        assertEquals(listOf("路由 192.0.2.0/24"), peer.routes)
    }

    /** 查看其他网络或历史 applied 不生成本机/peer；旧代或 Stale 核心不生成在线设备和 NAT 事实。 */
    @Test fun missingStaleAndOtherSessionObservations_doNotCreateDevices() {
        val current = session()
        val other = DevicesViewModel.project(current, LocalProfileId("00000000-0000-4000-8000-000000000002"))
        assertNull(other.local)
        assertTrue(other.peers.isEmpty())
        val history = DevicesViewModel.project(current.copy(applied = applied.copy(freshness = DataFreshness.Stale)), saved.profileId)
        assertNull(history.local)
        assertTrue(history.peers.isEmpty())
        assertEquals("当前查看网络运行结果未确认", history.notice)
        listOf(
            current.copy(core = null),
            current.copy(core = current.core!!.copy(freshness = DataFreshness.Stale)),
            current.copy(core = Observation(snapshot(SessionId("old", 2)), 1000, DataFreshness.Fresh)),
        ).forEach { missing ->
            val state = DevicesViewModel.project(missing, saved.profileId)
            assertTrue(state.peers.isEmpty())
            assertNull(state.local!!.udpNat)
            assertNull(state.local.tcpNat)
            assertEquals("其他设备暂无采样", state.peersNotice)
        }
        val empty = DevicesViewModel.project(SessionState(), saved.profileId)
        assertNull(empty.local)
        assertTrue(empty.peers.isEmpty())
    }
}
