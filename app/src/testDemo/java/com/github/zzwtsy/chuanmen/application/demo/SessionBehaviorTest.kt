package com.github.zzwtsy.chuanmen.application.demo

import com.github.zzwtsy.chuanmen.contracts.ConnectionPhase
import com.github.zzwtsy.chuanmen.contracts.EngineCommand
import com.github.zzwtsy.chuanmen.contracts.OperationResult
import com.github.zzwtsy.chuanmen.contracts.UnavailableReason
import com.github.zzwtsy.chuanmen.contracts.DataFreshness
import com.github.zzwtsy.chuanmen.contracts.FailureDisposition
import com.github.zzwtsy.chuanmen.contracts.OperationKind
import com.github.zzwtsy.chuanmen.contracts.SessionTimeouts
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionBehaviorTest {
    /** 固定连接脚本在 500 毫秒回执；推进到 499 毫秒仍 Connecting，到边界执行任务后才 Running。 */
    @Test
    fun connect_waitsForReceipt() = runTest {
        val h = ScenarioHarness(this, DemoFixtures.disconnected)
        h.sessions.connect(DemoFixtures.savedA)
        assertEquals(ConnectionPhase.Preparing, h.sessions.state.value.phase)
        runCurrent()
        assertEquals(ConnectionPhase.Connecting, h.sessions.state.value.phase)
        advanceTimeBy(499); runCurrent()
        assertNull(h.sessions.state.value.applied)
        advanceTimeBy(1); runCurrent()
        assertEquals(ConnectionPhase.Running, h.sessions.state.value.phase)
        assertEquals(500L, h.sessions.state.value.applied!!.virtualIpv4.observedAtMillis)
    }

    /** 连接失败 fixture 在回执时返回结构化错误，断言失败状态可见且不存在伪造 applied。 */
    @Test
    fun connectionFailure_doesNotClaimRunning() = runTest {
        val h = ScenarioHarness(this, DemoFixtures.connectionFailure)
        h.sessions.connect(DemoFixtures.savedA)
        runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(ConnectionPhase.Failed, h.sessions.state.value.phase)
        assertNull(h.sessions.state.value.applied)
        assertEquals(UnavailableReason.EngineRejected, h.sessions.state.value.failure!!.reason)
    }

    /** A 正在运行时请求连接 B，断言返回明确拒绝、A 的快照不变且未提交引擎命令。 */
    @Test
    fun otherProfile_requiresExplicitDisconnect() = runTest {
        val h = ScenarioHarness(this, DemoFixtures.connected)
        val before = h.sessions.state.value
        assertEquals(OperationResult.Rejected(UnavailableReason.DifferentRunningProfile), h.sessions.connect(DemoFixtures.savedB))
        assertEquals(before, h.sessions.state.value)
        assertTrue(h.engine.submittedCommands.isEmpty())
    }

    /** 取消 A 后连接静态地址的 B，断言 B 回执采用其地址，A 的晚到 Ready 不能覆盖 B 的新代次快照。 */
    @Test
    fun lateStart_doesNotOverwriteNewGeneration() = runTest {
        val fixture = DemoFixtures.disconnected.copy(script = EngineScript(starts = listOf(ScriptReply.Ready(800), ScriptReply.Ready(200))))
        val h = ScenarioHarness(this, fixture)
        h.sessions.connect(DemoFixtures.savedA)
        runCurrent()
        h.sessions.disconnect()
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(ConnectionPhase.Idle, h.sessions.state.value.phase)
        h.sessions.connect(DemoFixtures.savedB)
        runCurrent(); advanceTimeBy(200); runCurrent()
        val activeB = h.sessions.state.value.applied!!
        assertEquals(DemoFixtures.profileB, activeB.saved.profileId)
        assertEquals("10.42.1.8", activeB.virtualIpv4.value)
        assertEquals(2L, activeB.sessionId.generation)
        advanceTimeBy(500); runCurrent()
        assertEquals(activeB, h.sessions.state.value.applied)
    }

    /** 静默脚本在超时前无失败，达到 300 毫秒时失败并提交停止；后续不会生成运行快照。 */
    @Test
    fun timeout_isControlledByVirtualTime() = runTest {
        val fixture = DemoFixtures.disconnected.copy(script = EngineScript(starts = listOf(ScriptReply.Silent)))
        val h = ScenarioHarness(this, fixture, timeoutMillis = 300)
        h.sessions.connect(DemoFixtures.savedA)
        runCurrent(); advanceTimeBy(299); runCurrent()
        assertNull(h.sessions.state.value.failure)
        advanceTimeBy(1); runCurrent()
        assertEquals(UnavailableReason.TimedOut, h.sessions.state.value.failure!!.reason)
        assertTrue(h.engine.submittedCommands.last() is EngineCommand.Stop)
        advanceTimeBy(1000); runCurrent()
        assertNull(h.sessions.state.value.applied)
        assertEquals(ConnectionPhase.Failed, h.sessions.state.value.phase)
    }

    /** 运行时重复断开合并为一次停止，回执到达后清除 applied，之后断开也不增加命令。 */
    @Test
    fun disconnect_isIdempotentAndClearsApplied() = runTest {
        val h = ScenarioHarness(this, DemoFixtures.connected)
        h.sessions.disconnect()
        assertEquals(OperationResult.Unchanged, h.sessions.disconnect())
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(ConnectionPhase.Idle, h.sessions.state.value.phase)
        assertNull(h.sessions.state.value.applied)
        assertEquals(OperationResult.Unchanged, h.sessions.disconnect())
        assertEquals(1, h.engine.submittedCommands.size)
    }

    /** 两个实例在同一虚拟时间运行同一 fixture，断言准备、连接中和成功快照逐项一致。 */
    @Test
    fun sameFixture_producesSameStates() = runTest {
        val first = ScenarioHarness(this, DemoFixtures.disconnected)
        val second = ScenarioHarness(this, DemoFixtures.disconnected)
        first.sessions.connect(DemoFixtures.savedA); second.sessions.connect(DemoFixtures.savedA)
        assertEquals(first.sessions.state.value, second.sessions.state.value)
        runCurrent()
        assertEquals(first.sessions.state.value, second.sessions.state.value)
        advanceTimeBy(500); runCurrent()
        assertEquals(first.sessions.state.value, second.sessions.state.value)
    }

    /** Apply 超时后保留历史快照并拒绝再次应用，只有匹配的迟到确认才能确认新修订。 */
    @Test
    fun lateApply_resolvesUnknownWithoutAllowingConcurrentApply() = runTest {
        val fixture = DemoFixtures.connected.copy(script = EngineScript(applies = listOf(ScriptReply.Ready(1000), ScriptReply.Ready(100))))
        val h = ScenarioHarness(this, fixture, timeoutMillis = 300)
        val second = h.repository.save(DemoFixtures.profileA, DemoFixtures.savedA.core.copy(mtu = 1400), DemoFixtures.savedA.vpnPolicy)!!
        h.sessions.apply(second)
        runCurrent(); advanceTimeBy(300); runCurrent()
        assertEquals(UnavailableReason.TimedOut, h.sessions.state.value.failure!!.reason)
        assertEquals(DemoFixtures.savedA, h.sessions.state.value.applied!!.saved)
        assertEquals(ConnectionPhase.OutcomeUnknown, h.sessions.state.value.phase)
        assertEquals(DataFreshness.Stale, h.sessions.state.value.applied!!.virtualIpv4.freshness)
        assertEquals(DataFreshness.Stale, h.sessions.state.value.applied!!.freshness)
        val third = h.repository.save(DemoFixtures.profileA, DemoFixtures.savedA.core.copy(mtu = 1500), DemoFixtures.savedA.vpnPolicy)!!
        assertEquals(OperationResult.Rejected(UnavailableReason.Busy), h.sessions.apply(third))
        advanceTimeBy(700); runCurrent()
        assertEquals(second, h.sessions.state.value.applied!!.saved)
        assertEquals(ConnectionPhase.Running, h.sessions.state.value.phase)
        assertEquals(third, h.repository.findSaved(DemoFixtures.profileA))
    }

    /** 分阶段启动在地址和 TUN 准备阶段都不提交 applied，到独立 Ready 回执才 Running。 */
    @Test
    fun progress_doesNotClaimRunningBeforeFinalReceipt() = runTest {
        val h = ScenarioHarness(this, DemoFixtures.stagedConnection)
        assertTrue(h.sessions.connect(DemoFixtures.savedA) is OperationResult.Accepted)
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(ConnectionPhase.AwaitingAddress, h.sessions.state.value.phase)
        assertNull(h.sessions.state.value.applied)
        advanceTimeBy(200); runCurrent()
        assertEquals(ConnectionPhase.PreparingTun, h.sessions.state.value.phase)
        assertNull(h.sessions.state.value.applied)
        advanceTimeBy(200); runCurrent()
        assertEquals(ConnectionPhase.Running, h.sessions.state.value.phase)
    }

    /** 地址等待中取消后，晚到的 TUN 进度与 Ready 不能把已确认停止的会话重新发布为运行。 */
    @Test
    fun cancelDuringAddressWait_ignoresLaterProgressAndReady() = runTest {
        val h = ScenarioHarness(this, DemoFixtures.stagedConnection)
        h.sessions.connect(DemoFixtures.savedA)
        runCurrent(); advanceTimeBy(100); runCurrent()
        h.sessions.disconnect()
        runCurrent(); advanceTimeBy(400); runCurrent()
        assertEquals(ConnectionPhase.Idle, h.sessions.state.value.phase)
        assertNull(h.sessions.state.value.applied)
    }

    /** 连接受理但尚未完成时重复连接被拒绝，后台只收到一次 Start。 */
    @Test
    fun repeatedConnect_doesNotCreateAnotherStart() = runTest {
        val h = ScenarioHarness(this, DemoFixtures.disconnected)
        h.sessions.connect(DemoFixtures.savedA)
        assertEquals(OperationResult.Rejected(UnavailableReason.Busy), h.sessions.connect(DemoFixtures.savedA))
        runCurrent()
        assertEquals(1, h.engine.submittedCommands.count { it is EngineCommand.Start })
    }

    /** 停止超过预算后保留旧会话和停止操作，重复停止不提交新任务，迟到确认仍清除 applied。 */
    @Test
    fun stopTimeout_preservesSubscriptionUntilLateConfirmation() = runTest {
        val h = ScenarioHarness(this, DemoFixtures.connected.copy(script = EngineScript(stopDelayMillis = 800)), 300)
        h.sessions.disconnect()
        runCurrent(); advanceTimeBy(300); runCurrent()
        assertEquals(ConnectionPhase.OutcomeUnknown, h.sessions.state.value.phase)
        assertEquals(OperationKind.Stop, h.sessions.state.value.pending)
        assertEquals(DataFreshness.Stale, h.sessions.state.value.applied!!.virtualIpv4.freshness)
        assertEquals(OperationResult.Rejected(UnavailableReason.Busy), h.sessions.connect(DemoFixtures.savedB))
        assertEquals(OperationResult.Unchanged, h.sessions.disconnect())
        assertEquals(1, h.engine.submittedCommands.count { it is EngineCommand.Stop })
        advanceTimeBy(500); runCurrent()
        assertEquals(ConnectionPhase.Idle, h.sessions.state.value.phase)
        assertNull(h.sessions.state.value.applied)
    }

    /** 明确返回未确认停止时不能清空 applied，也不能用已发出停止请求解锁下一次连接。 */
    @Test
    fun unknownStop_blocksRestartAndRetainsHistoricalSnapshot() = runTest {
        val h = ScenarioHarness(this, DemoFixtures.connected.copy(script = EngineScript(stopConfirms = false)))
        h.sessions.disconnect()
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(ConnectionPhase.OutcomeUnknown, h.sessions.state.value.phase)
        assertEquals(DemoFixtures.savedA, h.sessions.state.value.applied!!.saved)
        assertEquals(OperationResult.Rejected(UnavailableReason.Busy), h.sessions.connect(DemoFixtures.savedB))
        assertTrue(h.engine.submittedCommands.last() is EngineCommand.QueryRuntime)
    }

    /** 部分初始化失败的资源事实未知时保留代次，显式停止确认后才允许下一轮连接。 */
    @Test
    fun partialInitializationFailure_requiresConfirmedCleanup() = runTest {
        val script = EngineScript(starts = listOf(ScriptReply.Failed(disposition = FailureDisposition.Unknown)))
        val h = ScenarioHarness(this, DemoFixtures.disconnected.copy(script = script))
        h.sessions.connect(DemoFixtures.savedA)
        runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(ConnectionPhase.OutcomeUnknown, h.sessions.state.value.phase)
        assertNull(h.sessions.state.value.applied)
        assertEquals(OperationResult.Rejected(UnavailableReason.Busy), h.sessions.connect(DemoFixtures.savedB))
        h.sessions.disconnect()
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(ConnectionPhase.Idle, h.sessions.state.value.phase)
        assertTrue(h.sessions.connect(DemoFixtures.savedB) is OperationResult.Accepted)
    }

    /** 授权拒绝和服务撤销的结构化失败不建立运行快照，保留已保存输入。 */
    @Test
    fun permissionDenialAndRevoke_doNotClaimRunning() = runTest {
        listOf(UnavailableReason.PermissionDenied, UnavailableReason.ServiceRevoked).forEach { reason ->
            val h = ScenarioHarness(this, DemoFixtures.disconnected.copy(script = EngineScript(starts = listOf(ScriptReply.Failed(reason)))))
            h.sessions.connect(DemoFixtures.savedA)
            runCurrent(); advanceTimeBy(500); runCurrent()
            assertEquals(ConnectionPhase.Failed, h.sessions.state.value.phase)
            assertEquals(reason, h.sessions.state.value.failure!!.reason)
            assertNull(h.sessions.state.value.applied)
            assertEquals(DemoFixtures.savedA, h.repository.findSaved(DemoFixtures.profileA))
        }
    }

    /** 核心已应用但成功回执丢失时，超时查询必须核对操作身份和实际修订，再确认 applied。 */
    @Test
    fun lostApplyReceipt_isReconciledByRuntimeQuery() = runTest {
        val script = EngineScript(
            starts = listOf(ScriptReply.Ready(100)),
            applies = listOf(ScriptReply.Ready(100, deliverReceipt = false)),
        )
        val h = ScenarioHarness(this, DemoFixtures.disconnected.copy(script = script), 300)
        h.sessions.connect(DemoFixtures.savedA)
        runCurrent(); advanceTimeBy(100); runCurrent()
        val saved = h.repository.save(DemoFixtures.profileA, DemoFixtures.savedA.core.copy(mtu = 1400), DemoFixtures.savedA.vpnPolicy)!!
        h.sessions.apply(saved)
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(DemoFixtures.savedA, h.sessions.state.value.applied!!.saved)
        advanceTimeBy(200); runCurrent()
        assertTrue(h.engine.submittedCommands.last() is EngineCommand.QueryRuntime)
        assertEquals(ConnectionPhase.Running, h.sessions.state.value.phase)
        assertEquals(saved, h.sessions.state.value.applied!!.saved)
        assertEquals(saved.revision, h.sessions.state.value.runtime!!.value!!.appliedRevision)
        assertEquals(1400, h.sessions.state.value.runtime!!.value!!.interfaceSpec.value!!.mtu)
    }

    /** TUN 准备使用独立的 50 毫秒预算，达到边界后转停止，并等待停止确认才清除资源事实。 */
    @Test
    fun stageTimeout_usesTunBudgetAndWaitsForStopReceipt() = runTest {
        val h = ScenarioHarness(
            this, DemoFixtures.stagedConnection,
            timeouts = SessionTimeouts(1000, 1000, 50, 200),
        )
        h.sessions.connect(DemoFixtures.savedA)
        runCurrent(); advanceTimeBy(300); runCurrent()
        assertEquals(ConnectionPhase.PreparingTun, h.sessions.state.value.phase)
        advanceTimeBy(49); runCurrent()
        assertNull(h.sessions.state.value.failure)
        advanceTimeBy(1); runCurrent()
        assertEquals(ConnectionPhase.Stopping, h.sessions.state.value.phase)
        assertEquals(OperationKind.Stop, h.sessions.state.value.pending)
        assertEquals(UnavailableReason.TimedOut, h.sessions.state.value.failure!!.reason)
        advanceTimeBy(100); runCurrent()
        assertEquals(ConnectionPhase.Failed, h.sessions.state.value.phase)
        assertNull(h.sessions.state.value.sessionId)
        advanceTimeBy(50); runCurrent()
        assertEquals(ConnectionPhase.Failed, h.sessions.state.value.phase)
    }

    /** 应用无回执而查询仍报告旧运行操作时，不能把核心仍运行误认为新修订应用成功。 */
    @Test
    fun queryOfPreviousRunningOperation_doesNotResolvePendingApply() = runTest {
        val script = EngineScript(starts = listOf(ScriptReply.Ready(100)), applies = listOf(ScriptReply.Silent))
        val h = ScenarioHarness(this, DemoFixtures.disconnected.copy(script = script), 300)
        h.sessions.connect(DemoFixtures.savedA)
        runCurrent(); advanceTimeBy(100); runCurrent()
        val saved = h.repository.save(DemoFixtures.profileA, DemoFixtures.savedA.core.copy(mtu = 1400), DemoFixtures.savedA.vpnPolicy)!!
        val accepted = h.sessions.apply(saved) as OperationResult.Accepted
        runCurrent(); advanceTimeBy(300); runCurrent()
        assertEquals(ConnectionPhase.OutcomeUnknown, h.sessions.state.value.phase)
        assertEquals(1L, h.sessions.state.value.runtime!!.value!!.appliedRevision)
        assertNotEquals(accepted.token, h.sessions.state.value.runtime!!.value!!.lastOperation)
        assertEquals(DemoFixtures.savedA, h.sessions.state.value.applied!!.saved)
        assertEquals(OperationResult.Rejected(UnavailableReason.Busy), h.sessions.apply(saved))
        h.sessions.disconnect()
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(ConnectionPhase.Idle, h.sessions.state.value.phase)
        assertEquals(saved, h.repository.findSaved(DemoFixtures.profileA))
    }
}
