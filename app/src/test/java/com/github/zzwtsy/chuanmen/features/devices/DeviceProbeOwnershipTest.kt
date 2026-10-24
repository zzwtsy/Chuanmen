package com.github.zzwtsy.chuanmen.features.devices

import androidx.lifecycle.ViewModelStore
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.vpn.*
import com.github.zzwtsy.chuanmen.testAppliedSnapshot
import com.github.zzwtsy.chuanmen.testSavedRevision
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceProbeOwnershipTest {
    private val applied = testAppliedSnapshot(testSavedRevision())

    private fun session(attachmentSequence: Long = 1, ip: String = "10.42.0.2", present: Boolean = true): SessionState {
        val attachment = AttachmentId(applied.sessionId, attachmentSequence)
        val token = OperationToken(applied.sessionId, 1)
        val spec = VpnInterfaceSpec(listOf(Ipv4Cidr("10.42.0.1", 24)), listOf(Ipv4Cidr("10.42.0.0", 24)), emptyList(), 1380)
        val core = CoreSnapshot(applied.sessionId, token, 1, RuntimeStatus.Running, spec.addresses.single(),
            if (present) listOf(CorePeer(2, 1, networkRole = PeerNetworkRole.Member)) else emptyList(),
            if (present) listOf(CoreRoute(2, Ipv4Cidr(ip, 32), emptyList(), 1, "synthetic")) else emptyList(),
            null, null, attachment, TunBorrowState.Installed, null, 1, 0, 1, 0, 0)
        val runtime = RuntimeSnapshot(applied.sessionId, token, RuntimeStatus.Running, 1, attachment,
            Observation(spec, 0, DataFreshness.Fresh), Observation(null, null, DataFreshness.Unavailable, UnavailableReason.NoMeasurement))
        return SessionState(ConnectionPhase.Running, applied.sessionId, applied.saved.profileId, applied,
            core = Observation(core, 0, DataFreshness.Fresh), runtime = Observation(runtime, 0, DataFreshness.Fresh))
    }

    private inner class Harness(test: TestScope, partial: Boolean = false) {
        val sessions = MutableStateFlow(session())
        val viewed = MutableStateFlow<LocalProfileId?>(applied.saved.profileId)
        val store = ViewModelStore()
        var closes = 0
        val probes = ConnectionProbe(test.backgroundScope, sessions, ProbeTransportFactory { target -> object : ProbeTransport {
            override val identifier = 7
            var sequence = 0
            var nonce = 0L
            override suspend fun send(sequence: Int, nonce: Long) { this.sequence = sequence; this.nonce = nonce }
            override suspend fun receive(timeoutMillis: Long): EchoReply {
                if (partial) { if (sequence == 1) delay(10) else awaitCancellation() }
                return EchoReply(target.ipv4, identifier, sequence, nonce)
            }
            override fun close() { closes++ }
        } }, MonotonicClock { test.testScheduler.currentTime }, StandardTestDispatcher(test.testScheduler))
        val model = DevicesViewModel(sessions, viewed, probes).also {
            store.put("devices", it)
            test.backgroundScope.launch(UnconfinedTestDispatcher(test.testScheduler)) { it.state.collect {} }
        }
        fun open() { model.act(DeviceAction.Details(model.state.value.peers.single().identity!!)) }
    }

    /** 同会话同 peer 分别更换 IP、attachment 或二者时，旧 Complete 不得出现在新的完整目标下。 */
    @Test fun completedProbeMustMatchCurrentAttachmentAndAddress() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for ((attachment, ip) in listOf(1L to "10.42.0.3", 2L to "10.42.0.2", 2L to "10.42.0.3")) {
                val h = Harness(this)
                try {
                    runCurrent(); h.open(); h.model.act(DeviceAction.StartProbe); runCurrent()
                    assertEquals(ProbeStatus.Complete, h.model.state.value.probe?.status)
                    h.sessions.value = session(attachment, ip); runCurrent()
                    assertEquals(ip, h.model.state.value.detail?.probeTarget?.ipv4)
                    assertEquals(attachment, h.model.state.value.detail?.probeTarget?.attachmentId?.sequence)
                    assertNull(h.model.state.value.probe)
                    assertEquals(1, h.closes)
                } finally { h.store.clear(); runCurrent() }
            }
        } finally { Dispatchers.resetMain() }
    }

    /** 第二次发送期间设备消失时保留 1/2 的取消结果并标为历史；旧详情禁用复制和新探测。 */
    @Test fun disappearingPeerKeepsCancelledPartialResultAsHistory() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = Harness(this, partial = true)
        try {
            runCurrent(); h.open(); h.model.act(DeviceAction.StartProbe)
            advanceTimeBy(20); runCurrent()
            h.sessions.value = session(present = false); runCurrent()
            val state = h.model.state.value
            assertEquals(ProbeStatus.Cancelled, state.probe?.status)
            assertEquals(2, state.probe?.sent)
            assertEquals(1, state.probe?.received)
            assertTrue(state.probeHistorical)
            assertFalse(state.detail!!.online)
            assertNull(state.detail.probeTarget)
            assertEquals("10.42.0.2", state.probe?.target?.ipv4)
            assertEquals(1, h.closes)
        } finally { h.store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    /** 关闭后重新打开同一目标不接收进程残留结果；查看网络变化还会取消本页正在运行的任务。 */
    @Test fun reopeningAndChangingViewedNetworkRespectTaskOwnership() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = Harness(this, partial = true)
        try {
            runCurrent(); h.open(); h.model.act(DeviceAction.StartProbe)
            advanceTimeBy(20); runCurrent()
            h.model.act(DeviceAction.CloseDetails); runCurrent()
            assertEquals(ProbeStatus.Cancelled, h.probes.result.value?.status)
            h.open(); runCurrent()
            assertNull(h.model.state.value.probe)
            h.model.act(DeviceAction.StartProbe); runCurrent()
            assertEquals(ProbeStatus.Running, h.model.state.value.probe?.status)
            h.viewed.value = null; runCurrent()
            assertNull(h.model.state.value.detail)
            assertNull(h.model.state.value.probe)
            assertEquals(ProbeStatus.Cancelled, h.probes.result.value?.status)
            assertEquals(2, h.closes)
        } finally { h.store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}
