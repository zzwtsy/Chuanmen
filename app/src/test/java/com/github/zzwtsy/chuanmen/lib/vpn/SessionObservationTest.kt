package com.github.zzwtsy.chuanmen.lib.vpn

import com.github.zzwtsy.chuanmen.features.devices.DevicesViewModel
import com.github.zzwtsy.chuanmen.features.overview.OverviewViewModel
import com.github.zzwtsy.chuanmen.testAppliedSnapshot
import com.github.zzwtsy.chuanmen.testSavedRevision
import org.junit.Assert.*
import org.junit.Test

class SessionObservationTest {
    private val saved = testSavedRevision()
    private val applied = testAppliedSnapshot(saved)
    private fun session(peers: List<CorePeer>, routes: List<CoreRoute> = emptyList()): SessionState {
        val core = CoreSnapshot(applied.sessionId, OperationToken(applied.sessionId, 1), 1, RuntimeStatus.Running,
            null, peers, routes, null, null, null, null, null, 1, 0, 1, 0, 0, localPeerId = 1)
        return SessionState(ConnectionPhase.Running, applied.sessionId, saved.profileId, applied,
            core = Observation(core, 0, DataFreshness.Fresh))
    }

    /** 只有公共中继时总览仍等待同网成员；设备页单列中继，不生成成员 IP 或探测目标。 */
    @Test fun publicRelayIsSeparateFromOnlineMembers() {
        val session = session(listOf(CorePeer(1, 1, networkRole = PeerNetworkRole.Member), CorePeer(9, 2, networkRole = PeerNetworkRole.Relay)))
        val overview = OverviewViewModel.project(session, saved.profileId, true)
        assertEquals("0", overview.onlineDevices)
        assertTrue(overview.waitingForPeers)
        val devices = DevicesViewModel.project(session, saved.profileId)
        assertTrue(devices.peers.isEmpty())
        assertEquals(9L, devices.relays.single().identity!!.peerId)
        assertNull(devices.relays.single().ip)
        assertNull(devices.relays.single().probeTarget)
    }

    /** 重复成员连接仅计一次，间接路由成员也计入；未知邻居不猜测为成员或中继。 */
    @Test fun oneIndexServesMembersRelaysAndUnknownConnections() {
        val session = session(listOf(CorePeer(2, 1, networkRole = PeerNetworkRole.Member), CorePeer(2, 2, networkRole = PeerNetworkRole.Member),
            CorePeer(9, 1, networkRole = PeerNetworkRole.Relay), CorePeer(8, 1)),
            listOf(CoreRoute(3, Ipv4Cidr("10.42.0.3", 32), emptyList(), 2, "synthetic")))
        val index = SessionObservation.from(session, saved.profileId).index!!
        assertEquals(setOf(2L, 3L), index.members)
        assertEquals(setOf(9L), index.relays)
        assertEquals(setOf(8L), index.unknown)
        assertEquals("2（已确认）", OverviewViewModel.project(session, saved.profileId, true).onlineDevices)
        val stale = session.copy(core = session.core!!.copy(freshness = DataFreshness.Stale))
        assertNull(SessionObservation.from(stale, saved.profileId).index)
        assertNull(SessionObservation.from(session, null).index)
    }
}
