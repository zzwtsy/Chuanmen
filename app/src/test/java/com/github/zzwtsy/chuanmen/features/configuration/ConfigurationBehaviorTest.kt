package com.github.zzwtsy.chuanmen.features.configuration

import com.github.zzwtsy.chuanmen.features.configuration.lib.ConfigurationEditor
import com.github.zzwtsy.chuanmen.features.configuration.lib.DraftField
import com.github.zzwtsy.chuanmen.features.configuration.lib.EditorMode
import com.github.zzwtsy.chuanmen.lib.profile.Ipv4Assignment
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.MemoryProfileRepository
import com.github.zzwtsy.chuanmen.lib.profile.ProfileSummary
import com.github.zzwtsy.chuanmen.lib.vpn.ConnectionPhase
import com.github.zzwtsy.chuanmen.lib.vpn.RecordingSessions
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import com.github.zzwtsy.chuanmen.testAppliedSnapshot
import com.github.zzwtsy.chuanmen.testSavedRevision
import org.junit.Assert.*
import org.junit.Test

class ConfigurationBehaviorTest {
    /** A 正在运行时改本地名称，断言核心身份、保存修订和 applied 均不变，且未提交引擎命令。 */
    @Test
    fun rename_doesNotReconnectOrChangeIdentity() {
        val h = EditorHarness()
        val old = h.sessions.state.value.applied
        assertTrue(h.repository.rename(h.savedA.profileId, "我的本地名称"))
        assertEquals("我的本地名称", h.repository.profiles.value.first().displayName)
        assertEquals(h.savedA, h.repository.findSaved(h.savedA.profileId))
        assertEquals(old, h.sessions.state.value.applied)
        assertTrue(h.sessions.requests.isEmpty())
    }

    /** 运行中修改并仅保存 MTU，断言 saved 增加修订，applied 保持旧版本且没有重连命令。 */
    @Test
    fun save_updatesOnlySavedRevision() {
        val h = EditorHarness()
        h.editor.open(h.savedA.profileId)
        h.editor.edit(h.savedA.profileId, DraftField.Mtu, "1400")
        val saved = h.editor.save(h.savedA.profileId)!!
        assertEquals(2L, saved.revision)
        assertEquals(1400, saved.core.mtu)
        assertEquals(h.savedA, h.sessions.state.value.applied!!.saved)
        assertFalse(h.editor.drafts.value.getValue(h.savedA.profileId).dirty)
        assertTrue(h.sessions.requests.isEmpty())
    }

    /** 静态地址配置输入无效 MTU 后切换完整/基础，断言 raw、错误和静态地址保留，保存被拒绝。 */
    @Test
    fun invalidInput_survivesModeSwitch() {
        val h = EditorHarness()
        h.editor.open(h.savedB.profileId)
        h.editor.edit(h.savedB.profileId, DraftField.Mtu, "bad mtu")
        h.editor.mode(h.savedB.profileId, EditorMode.Full)
        h.editor.mode(h.savedB.profileId, EditorMode.Basic)
        val draft = h.editor.drafts.value.getValue(h.savedB.profileId)
        assertEquals("bad mtu", draft.rawMtu)
        assertTrue(DraftField.Mtu in draft.errors)
        assertEquals(h.savedB.core.ipv4, draft.base.core.ipv4)
        assertNull(h.editor.save(h.savedB.profileId))
        assertEquals(h.savedB, h.repository.findSaved(h.savedB.profileId))
    }

    /** 本地改名并保存新参数后恢复运行配置，断言名称保留、核心字段恢复而 applied 未被保存操作改写。 */
    @Test
    fun restore_preservesLocalName() {
        val h = EditorHarness()
        h.repository.rename(h.savedA.profileId, "家里")
        h.editor.open(h.savedA.profileId)
        h.editor.edit(h.savedA.profileId, DraftField.Mtu, "1400")
        h.editor.save(h.savedA.profileId)
        val restored = h.editor.restore(h.sessions.state.value.applied!!.saved)!!
        assertEquals("家里", h.repository.profiles.value.first().displayName)
        assertEquals(h.savedA.core, restored.core)
        assertEquals(3L, restored.revision)
        assertEquals(1L, h.sessions.state.value.applied!!.saved.revision)
        assertTrue(h.sessions.requests.isEmpty())
    }

    private class EditorHarness {
        val savedA = testSavedRevision()
        val savedB = testSavedRevision(
            LocalProfileId("00000000-0000-4000-8000-000000000002"), Ipv4Assignment.Static("10.42.1.8"),
        )
        val repository = MemoryProfileRepository(
            listOf(ProfileSummary(savedA, "网络 A"), ProfileSummary(savedB, "网络 B")),
        )
        private val applied = testAppliedSnapshot(savedA)
        val sessions = RecordingSessions(SessionState(
            phase = ConnectionPhase.Running, sessionId = applied.sessionId,
            requestedProfileId = savedA.profileId, applied = applied,
        ))
        val editor = ConfigurationEditor(repository)
    }
}
