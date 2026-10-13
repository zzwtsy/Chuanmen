package com.github.zzwtsy.chuanmen.feature.configuration.edit

import com.github.zzwtsy.chuanmen.application.demo.DemoFixtures
import com.github.zzwtsy.chuanmen.application.demo.EngineScript
import com.github.zzwtsy.chuanmen.application.demo.ScenarioHarness
import com.github.zzwtsy.chuanmen.application.demo.ScriptReply
import com.github.zzwtsy.chuanmen.contracts.ConnectionPhase
import com.github.zzwtsy.chuanmen.contracts.OperationKind
import com.github.zzwtsy.chuanmen.contracts.UnavailableReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationBehaviorTest {
    /** A 正在运行时改本地名称，断言核心身份、保存修订和 applied 均不变，且未提交引擎命令。 */
    @Test
    fun rename_doesNotReconnectOrChangeIdentity() = runTest {
        val h = ScenarioHarness(this, DemoFixtures.connected)
        val old = h.sessions.state.value.applied
        assertTrue(h.repository.rename(DemoFixtures.profileA, "我的本地名称"))
        assertEquals("我的本地名称", h.repository.profiles.value.first().displayName)
        assertEquals(DemoFixtures.savedA, h.repository.findSaved(DemoFixtures.profileA))
        assertEquals(old, h.sessions.state.value.applied)
        assertTrue(h.engine.submittedCommands.isEmpty())
    }

    /** 运行中修改并仅保存 MTU，断言 saved 增加修订，applied 保持旧版本且没有重连命令。 */
    @Test
    fun save_updatesOnlySavedRevision() = runTest {
        val h = ScenarioHarness(this, DemoFixtures.connected)
        h.editor.open(DemoFixtures.profileA)
        h.editor.edit(DemoFixtures.profileA, DraftField.Mtu, "1400")
        val saved = h.editor.save(DemoFixtures.profileA)!!
        assertEquals(2L, saved.revision)
        assertEquals(1400, saved.core.mtu)
        assertEquals(DemoFixtures.savedA, h.sessions.state.value.applied!!.saved)
        assertFalse(h.editor.drafts.value.getValue(DemoFixtures.profileA).dirty)
        assertTrue(h.engine.submittedCommands.isEmpty())
    }

    /** 静态地址配置输入无效 MTU 后切换完整/基础，断言 raw、错误和静态地址保留，保存被拒绝。 */
    @Test
    fun invalidInput_survivesModeSwitch() = runTest {
        val h = ScenarioHarness(this, DemoFixtures.connected)
        h.editor.open(DemoFixtures.profileB)
        h.editor.edit(DemoFixtures.profileB, DraftField.Mtu, "bad mtu")
        h.editor.mode(DemoFixtures.profileB, EditorMode.Full)
        h.editor.mode(DemoFixtures.profileB, EditorMode.Basic)
        val draft = h.editor.drafts.value.getValue(DemoFixtures.profileB)
        assertEquals("bad mtu", draft.rawMtu)
        assertTrue(DraftField.Mtu in draft.errors)
        assertEquals(DemoFixtures.savedB.core.ipv4, draft.base.core.ipv4)
        assertNull(h.editor.save(DemoFixtures.profileB))
        assertEquals(DemoFixtures.savedB, h.repository.findSaved(DemoFixtures.profileB))
    }

    /** 本地改名并保存新参数后恢复运行配置，断言名称保留、核心字段恢复而 applied 未被保存操作改写。 */
    @Test
    fun restore_preservesLocalName() = runTest {
        val h = ScenarioHarness(this, DemoFixtures.connected)
        h.repository.rename(DemoFixtures.profileA, "家里")
        h.editor.open(DemoFixtures.profileA)
        h.editor.edit(DemoFixtures.profileA, DraftField.Mtu, "1400")
        h.editor.save(DemoFixtures.profileA)
        val restored = h.editor.restore(h.sessions.state.value.applied!!.saved)!!
        assertEquals("家里", h.repository.profiles.value.first().displayName)
        assertEquals(DemoFixtures.savedA.core, restored.core)
        assertEquals(3L, restored.revision)
        assertEquals(1L, h.sessions.state.value.applied!!.saved.revision)
        assertTrue(h.engine.submittedCommands.isEmpty())
    }

    /** 具名应用失败 fixture 保存新版本并 Apply，推进虚拟时间后断言 saved 保留新版本、Running 与旧 applied 保留。 */
    @Test
    fun failedApply_keepsPreviousAppliedSnapshot() = runTest {
        val h = ScenarioHarness(this, DemoFixtures.applyFailure)
        val old = h.sessions.state.value.applied
        val saved = h.repository.save(DemoFixtures.profileA, DemoFixtures.savedA.core.copy(mtu = 1400), DemoFixtures.savedA.vpnPolicy)!!
        h.sessions.apply(saved)
        assertEquals(old, h.sessions.state.value.applied)
        assertNull(h.sessions.state.value.failure)
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(ConnectionPhase.Running, h.sessions.state.value.phase)
        assertEquals(old, h.sessions.state.value.applied)
        assertEquals(saved, h.repository.findSaved(DemoFixtures.profileA))
        assertEquals(OperationKind.Apply, h.sessions.state.value.failure!!.kind)
        assertEquals(UnavailableReason.EngineRejected, h.sessions.state.value.failure!!.reason)
    }

    /** 第一次 Apply 失败、第二次脚本成功时，断言只有第二次成功回执更新 applied 并清除失败。 */
    @Test
    fun retryApply_updatesOnlyAfterSuccessfulReceipt() = runTest {
        val fixture = DemoFixtures.connected.copy(script = EngineScript(applies = listOf(ScriptReply.Failed(), ScriptReply.Ready())))
        val h = ScenarioHarness(this, fixture)
        val saved = h.repository.save(DemoFixtures.profileA, DemoFixtures.savedA.core.copy(mtu = 1400), DemoFixtures.savedA.vpnPolicy)!!
        h.sessions.apply(saved)
        runCurrent(); advanceTimeBy(500); runCurrent()
        assertEquals(DemoFixtures.savedA, h.sessions.state.value.applied!!.saved)
        h.sessions.apply(saved)
        runCurrent(); advanceTimeBy(499); runCurrent()
        assertEquals(DemoFixtures.savedA, h.sessions.state.value.applied!!.saved)
        advanceTimeBy(1); runCurrent()
        assertEquals(saved, h.sessions.state.value.applied!!.saved)
        assertNull(h.sessions.state.value.failure)
    }
}
