package com.github.zzwtsy.chuanmen.lib.vpn

import com.github.zzwtsy.chuanmen.testAppliedSnapshot
import com.github.zzwtsy.chuanmen.testSavedRevision
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionProbeTest {
    private val applied = testAppliedSnapshot(testSavedRevision())
    private val attachment = AttachmentId(applied.sessionId, 1)
    private val target = ProbeTarget(applied.sessionId, attachment, 2, "10.42.0.2")
    private fun session(): SessionState {
        val spec = VpnInterfaceSpec(listOf(Ipv4Cidr("10.42.0.1", 24)), listOf(Ipv4Cidr("10.42.0.0", 24)), emptyList(), 1380)
        val core = CoreSnapshot(applied.sessionId, OperationToken(applied.sessionId, 1), 1, RuntimeStatus.Running,
            spec.addresses.single(), listOf(CorePeer(2, 1)), listOf(CoreRoute(2, Ipv4Cidr(target.ipv4, 32), emptyList(), 1, "2.7.0")),
            null, null, attachment, TunBorrowState.Installed, null, 1, 0, 1, 0, 0)
        val runtime = RuntimeSnapshot(applied.sessionId, core.lastOperation, RuntimeStatus.Running, 1, attachment,
            Observation(spec, 0, DataFreshness.Fresh), Observation(null, null, DataFreshness.Unavailable, UnavailableReason.NoMeasurement))
        return SessionState(ConnectionPhase.Running, applied.sessionId, applied.saved.profileId, applied,
            core = Observation(core, 0, DataFreshness.Fresh), runtime = Observation(runtime, 0, DataFreshness.Fresh))
    }
    private class Socket(val target: ProbeTarget) : ProbeTransport {
        override val identifier = 37
        var sent = 0
        var closed = 0
        var nonce = 0L
        var failSend = false
        var receive: suspend Socket.(Long) -> EchoReply? = { delay(10); echo() }
        override suspend fun send(sequence: Int, nonce: Long) { if (failSend) error("synthetic-secret"); sent = sequence; this.nonce = nonce }
        override suspend fun receive(timeoutMillis: Long) = receive(this, timeoutMillis)
        fun echo() = EchoReply(target.ipv4, identifier, sent, nonce)
        override fun close() { closed++ }
    }
    private inner class Harness(test: TestScope, socket: Socket = Socket(target)) {
        val sessions = MutableStateFlow(session())
        val sockets = mutableListOf<Socket>()
        var nextSocket = socket
        val probe = ConnectionProbe(test.backgroundScope, sessions, ProbeTransportFactory { nextSocket.also { sockets += it } },
            MonotonicClock { test.testScheduler.currentTime }, StandardTestDispatcher(test.testScheduler))
        val result get() = probe.result.value!!
    }

    /** 四个串行请求各在 10 ms 后回复，统计为 4/4、0% 丢包且 socket 只关闭一次。 */
    @Test fun fourRepliesAreComplete() = runTest {
        val h = Harness(this)
        assertEquals(ProbeStart.Started, h.probe.start(target))
        advanceTimeBy(100); runCurrent()
        assertEquals(4, h.result.sent); assertEquals(4, h.result.received)
        assertEquals(listOf(10L, 10L, 10L, 10L), h.result.rttMillis)
        assertEquals(0.0, h.result.loss!!, 0.0)
        assertEquals(ProbeStatus.Complete, h.result.status)
        assertEquals(1, h.sockets.single().closed)
    }

    /** 相同完整目标重新启动后，携带旧任务 ID 的取消动作不能关闭新任务的 socket。 */
    @Test fun staleOwnerCannotCancelNewTaskForSameTarget() = runTest {
        val h = Harness(this, Socket(target).apply { receive = { awaitCancellation() } })
        h.probe.start(target); runCurrent()
        val oldId = h.result.taskId
        h.probe.cancel(target, oldId); runCurrent()
        val next = Socket(target).apply { receive = { awaitCancellation() } }
        h.nextSocket = next
        h.probe.start(target); runCurrent()
        h.probe.cancel(target, oldId); runCurrent()
        assertEquals(ProbeStatus.Running, h.result.status)
        assertEquals(0, next.closed)
        h.probe.cancel(target, h.result.taskId); runCurrent()
        assertEquals(ProbeStatus.Cancelled, h.result.status)
        assertEquals(1, next.closed)
    }

    /** 每次接收等待一秒仍无响应，四次请求为 0/4；不把目标设备状态改成离线。 */
    @Test fun fourTimeoutsAreMeasuredLoss() = runTest {
        val socket = Socket(target).apply { receive = { timeout -> delay(timeout); null } }
        val h = Harness(this, socket); val before = h.sessions.value
        h.probe.start(target); advanceTimeBy(4000); runCurrent()
        assertEquals(4, h.result.sent); assertEquals(0, h.result.received)
        assertEquals(1.0, h.result.loss!!, 0.0)
        assertEquals(ProbeStatus.Complete, h.result.status)
        assertEquals(before, h.sessions.value)
        assertEquals(1, socket.closed)
    }

    /** 第二个请求等待中取消保留 1/2 结果；Busy 不打开第二个 socket，取消后允许重启。 */
    @Test fun busyAndCancellationKeepPartialResults() = runTest {
        val socket = Socket(target).apply { receive = { if (sent == 1) { delay(10); echo() } else { awaitCancellation() } } }
        val h = Harness(this, socket)
        h.probe.start(target); advanceTimeBy(20); runCurrent()
        assertEquals(ProbeStart.Busy, h.probe.start(target))
        assertEquals(1, h.sockets.size)
        h.probe.cancel(target); runCurrent()
        assertEquals(2, h.result.sent); assertEquals(1, h.result.received)
        assertEquals(ProbeStatus.Cancelled, h.result.status)
        assertEquals(0.5, h.result.loss!!, 0.0)
        assertEquals(1, socket.closed)
        h.nextSocket = Socket(target)
        assertEquals(ProbeStart.Started, h.probe.start(target))
        advanceTimeBy(100); runCurrent()
        assertEquals(2, h.sockets.size)
        assertEquals(ProbeStatus.Complete, h.result.status)
        assertTrue(h.sockets.all { it.closed == 1 })
    }

    /** 首次发送抛异常时 sent 保持零、丢包未知，只报告安全分类并释放 socket。 */
    @Test fun sendFailureDoesNotCountAsSent() = runTest {
        val socket = Socket(target).apply { failSend = true }
        val h = Harness(this, socket); h.probe.start(target); runCurrent()
        assertEquals(0, h.result.sent); assertNull(h.result.loss)
        assertEquals(ProbeUnavailable.SendFailed, h.result.unavailable)
        assertFalse(h.result.toString().contains("synthetic-secret"))
        assertEquals(1, socket.closed)
    }

    /** 错误目标、标识、序号或旧 nonce 不增加回复数，匹配包才计算 RTT。 */
    @Test fun mismatchedPacketsAreIgnored() = runTest {
        var invalid = 0
        val socket = Socket(target).apply { receive = {
            delay(10)
            when (invalid++) {
                0 -> echo().copy(target = "10.42.0.3")
                1 -> echo().copy(identifier = 99)
                2 -> echo().copy(sequence = 9)
                3 -> echo().copy(nonce = -1)
                else -> echo()
            }
        } }
        val h = Harness(this, socket); h.probe.start(target); advanceTimeBy(100); runCurrent()
        assertEquals(4, h.result.received)
        assertEquals(50L, h.result.rttMillis.first())
        assertEquals(1, socket.closed)
    }

    /** 目标失去 Fresh 后立即关闭任务；旧任务迟到响应不得覆盖新任务结果。 */
    @Test fun staleSessionCancelsAndLateReplyCannotUpdateReplacement() = runTest {
        val socket = Socket(target).apply { receive = { withContext(NonCancellable) { delay(100); echo() } } }
        val h = Harness(this, socket); h.probe.start(target); runCurrent()
        h.sessions.value = h.sessions.value.copy(core = h.sessions.value.core!!.copy(freshness = DataFreshness.Stale))
        runCurrent()
        assertEquals(ProbeStatus.Cancelled, h.result.status)
        assertEquals(1, socket.closed)
        h.sessions.value = session(); h.nextSocket = Socket(target)
        h.probe.start(target); advanceTimeBy(200); runCurrent()
        assertEquals(2L, h.result.taskId)
        assertEquals(ProbeStatus.Complete, h.result.status)
        assertEquals(4, h.result.received)
        assertTrue(h.sockets.all { it.closed == 1 })
    }

    /** 非当前地址、没有 VPN 路由或非 Fresh 的输入在工厂前拒绝，不打开 socket。 */
    @Test fun eligibilityRejectsUnknownTargetsAndStaleData() = runTest {
        val h = Harness(this)
        assertEquals(ProbeStart.Unavailable, h.probe.start(target.copy(ipv4 = "192.0.2.1")))
        h.sessions.value = h.sessions.value.copy(core = h.sessions.value.core!!.copy(freshness = DataFreshness.Stale))
        assertEquals(ProbeStart.Unavailable, h.probe.start(target))
        assertEquals(0, h.sockets.size)
        assertNull(h.result.loss)
    }

    /** 构造 echo reply 后核对校验和、标识、序号和 nonce；损坏包和 echo request 拒绝。 */
    @Test fun datagramPacketCodecChecksIdentityAndChecksum() {
        val request = IcmpEcho.packet(37, 4, 123)
        assertNull(IcmpEcho.reply(request, target.ipv4))
        val reply = request.copyOf().apply { this[0] = 0; this[2] = 0; this[3] = 0 }
        val checksum = IcmpEcho.checksum(reply)
        reply[2] = (checksum ushr 8).toByte(); reply[3] = checksum.toByte()
        assertEquals(EchoReply(target.ipv4, 37, 4, 123), IcmpEcho.reply(reply, target.ipv4))
        reply[8] = (reply[8].toInt() xor 1).toByte()
        assertNull(IcmpEcho.reply(reply, target.ipv4))
    }
}
