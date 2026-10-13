package com.github.zzwtsy.chuanmen.application.demo

import com.github.zzwtsy.chuanmen.contracts.ConnectionPhase
import com.github.zzwtsy.chuanmen.contracts.EngineCommand
import com.github.zzwtsy.chuanmen.contracts.OperationResult
import com.github.zzwtsy.chuanmen.contracts.UnavailableReason
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

    /** 同一会话第一次 Apply 超时后重试新修订，旧回执晚到时不能覆盖新操作已确认的版本。 */
    @Test
    fun lateApply_doesNotOverwriteNewOperation() = runTest {
        val fixture = DemoFixtures.connected.copy(script = EngineScript(applies = listOf(ScriptReply.Ready(1000), ScriptReply.Ready(100))))
        val h = ScenarioHarness(this, fixture, timeoutMillis = 300)
        val second = h.repository.save(DemoFixtures.profileA, DemoFixtures.savedA.core.copy(mtu = 1400), DemoFixtures.savedA.vpnPolicy)!!
        h.sessions.apply(second)
        runCurrent(); advanceTimeBy(300); runCurrent()
        assertEquals(UnavailableReason.TimedOut, h.sessions.state.value.failure!!.reason)
        assertEquals(DemoFixtures.savedA, h.sessions.state.value.applied!!.saved)
        val third = h.repository.save(DemoFixtures.profileA, DemoFixtures.savedA.core.copy(mtu = 1500), DemoFixtures.savedA.vpnPolicy)!!
        h.sessions.apply(third)
        runCurrent(); advanceTimeBy(100); runCurrent()
        assertEquals(third, h.sessions.state.value.applied!!.saved)
        advanceTimeBy(600); runCurrent()
        assertEquals(third, h.sessions.state.value.applied!!.saved)
    }
}
