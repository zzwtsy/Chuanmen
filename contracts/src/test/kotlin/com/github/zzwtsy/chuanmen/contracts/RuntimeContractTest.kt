package com.github.zzwtsy.chuanmen.contracts

import org.junit.Assert.*
import org.junit.Test

class RuntimeContractTest {
    private val session = SessionId("runtime-contract", 1)
    private val spec = VpnInterfaceSpec(listOf(Ipv4Cidr("10.42.0.9", 24)), listOf(Ipv4Cidr("10.42.0.0", 24)), emptyList(), 1380)

    /** 超过 Double 精确整数范围的累计字节保持 Long 原值，零包计数仍是有效计数。 */
    @Test
    fun counters_preserve64BitIntegerPrecision() {
        val large = 9_007_199_254_740_993L
        assertEquals(large, TunCounters(large, Long.MAX_VALUE, 0, 1).txBytes)
        assertEquals(Long.MAX_VALUE, TunCounters(large, Long.MAX_VALUE, 0, 1).rxBytes)
        assertThrows(IllegalArgumentException::class.java) { TunCounters(-1, 0, 0, 0) }
    }

    /** 查询声明 Running 却缺 attachment 或只有过期接口时，不能构造当前运行快照。 */
    @Test
    fun runningSnapshot_requiresCurrentAttachmentAndFreshInterface() {
        val fresh = Observation(spec, 0, DataFreshness.Fresh)
        val missing = Observation<TunCounters>(null, null, DataFreshness.Unavailable, UnavailableReason.NotImplemented)
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeSnapshot(session, null, RuntimeStatus.Running, 1, null, fresh, missing)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeSnapshot(session, null, RuntimeStatus.Running, 1, AttachmentId(session, 1), fresh.copy(freshness = DataFreshness.Stale), missing)
        }
        val value = RuntimeSnapshot(session, OperationToken(session, 2), RuntimeStatus.Running, 1, AttachmentId(session, 1), fresh, missing)
        assertNull(value.tunCounters.value)
        assertEquals(UnavailableReason.NotImplemented, value.tunCounters.unavailableReason)
    }

    /** 新会话不能接收旧代次 attachment 或操作身份；Stopped 也不能继续宣称活动借用。 */
    @Test
    fun snapshot_rejectsCrossGenerationOwnership() {
        val old = SessionId(session.value, 2)
        val fresh = Observation(spec, 0, DataFreshness.Fresh)
        val missing = Observation<TunCounters>(null, null, DataFreshness.Unavailable, UnavailableReason.NoMeasurement)
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeSnapshot(session, null, RuntimeStatus.Starting, null, AttachmentId(old, 1), fresh, missing)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeSnapshot(session, OperationToken(old, 1), RuntimeStatus.Starting, null, null, fresh, missing)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RuntimeSnapshot(session, null, RuntimeStatus.Stopped, null, AttachmentId(session, 1), fresh, missing)
        }
    }

    /** 未排空资源或停止证明来自其他会话时，构造停止回执必须拒绝。 */
    @Test
    fun stopReceipt_requiresDrainAndMatchingSession() {
        assertThrows(IllegalArgumentException::class.java) { StopConfirmation(session, drained = false) }
        assertThrows(IllegalArgumentException::class.java) {
            EngineEvent.Stopped(OperationToken(session, 1), StopConfirmation(SessionId("other", 1), drained = true))
        }
        assertTrue(EngineEvent.Stopped(OperationToken(session, 1), StopConfirmation(session, drained = true)).confirmation.drained)
    }

    /** 地址进度不能伪装成 Ready；Ready 的地址缺失或过期时构造失败。 */
    @Test
    fun progressAndReady_haveDifferentCompletionSemantics() {
        EngineEvent.Progress(OperationToken(session, 1), ConnectionPhase.AwaitingAddress)
        assertThrows(IllegalArgumentException::class.java) { EngineEvent.Progress(OperationToken(session, 1), ConnectionPhase.Running) }
        assertThrows(IllegalArgumentException::class.java) {
            EngineEvent.Ready(OperationToken(session, 1), Observation("10.42.0.9", 0, DataFreshness.Stale))
        }
    }

    /** 默认预算对应启动、地址、TUN 和停止四个等待阶段；零或负预算均不可使用。 */
    @Test
    fun timeouts_keepStageBudgetsAndRejectNonPositiveValues() {
        assertEquals(SessionTimeouts(30_000, 30_000, 5_000, 10_000), SessionTimeouts())
        assertThrows(IllegalArgumentException::class.java) { SessionTimeouts(tunMillis = 0) }
    }
}
