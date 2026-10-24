package com.github.zzwtsy.chuanmen.features.configuration

import com.github.zzwtsy.chuanmen.features.configuration.lib.*
import com.github.zzwtsy.chuanmen.lib.profile.*
import com.github.zzwtsy.chuanmen.lib.vpn.*
import com.github.zzwtsy.chuanmen.testAppliedSnapshot
import com.github.zzwtsy.chuanmen.testSavedRevision
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationBehaviorTest {
    private val savedA = testSavedRevision()
    private val savedB = testSavedRevision(LocalProfileId("00000000-0000-4000-8000-000000000002"), Ipv4Assignment.Static("10.42.1.8"))
    private fun repository() = MemoryProfileRepository(listOf(ProfileSummary(savedA, "网络 A"), ProfileSummary(savedB, "网络 B")))
    private fun sessions(): RecordingSessions {
        val applied = testAppliedSnapshot(savedA)
        return RecordingSessions(SessionState(phase = ConnectionPhase.Running, sessionId = applied.sessionId, requestedProfileId = savedA.profileId, applied = applied))
    }

    /** A 正在运行时改名，生产动作只更新本地元数据，核心身份、保存修订及 applied 均不变。 */
    @Test fun rename_doesNotReconnectOrChangeIdentity() = runTest {
        withConfiguration(repository(), sessions()) { h ->
            val old = h.sessions.state.value.applied
            h.act(ConfigurationAction.Rename("我的本地名称"))
            assertEquals("我的本地名称", h.repository.profiles.value.first().displayName)
            assertEquals(savedA, h.repository.findSaved(savedA.profileId))
            assertEquals(old, h.sessions.state.value.applied)
            assertTrue(h.sessions.requests.isEmpty())
        }
    }
    /** 运行中仅保存 MTU，saved 增修订而 applied 保持旧版本，不发送重连命令。 */
    @Test fun save_updatesOnlySavedRevision() = runTest {
        withConfiguration(repository(), sessions()) { h ->
            h.edit(DraftField.Mtu, "1400"); h.act(ConfigurationAction.Save)
            val saved = h.repository.findSaved(savedA.profileId)!!
            assertEquals(2L, saved.revision); assertEquals(1400, saved.core.mtu)
            assertEquals(savedA, h.sessions.state.value.applied!!.saved)
            assertFalse(h.draft.dirty); assertTrue(h.sessions.requests.isEmpty())
        }
    }
    /** 静态地址配置输入无效 MTU 后切换标签，原文、错误和地址保留，生产保存不改变仓库。 */
    @Test fun invalidInput_survivesModeSwitch() = runTest {
        withConfiguration(repository()) { h ->
            h.view(savedB.profileId); h.edit(DraftField.Mtu, "bad mtu")
            h.act(ConfigurationAction.ChangeMode(EditorMode.Full)); h.act(ConfigurationAction.ChangeMode(EditorMode.Basic))
            h.act(ConfigurationAction.Save)
            assertEquals("bad mtu", h.draft.rawMtu); assertTrue(DraftField.Mtu in h.draft.errors)
            assertEquals(savedB.core.ipv4, h.draft.base.core.ipv4)
            assertEquals(savedB, h.repository.findSaved(savedB.profileId))
        }
    }
    /** 改名并保存后丢弃后续编辑，恢复最近 saved 而非历史 applied，本地名称保持不变。 */
    @Test fun discard_preservesNameAndLatestSaved() = runTest {
        withConfiguration(repository(), sessions()) { h ->
            h.act(ConfigurationAction.Rename("家里")); h.edit(DraftField.Mtu, "1400"); h.act(ConfigurationAction.Save)
            val latest = h.repository.findSaved(savedA.profileId)
            h.edit(DraftField.Mtu, "invalid"); h.act(ConfigurationAction.Discard)
            assertEquals("家里", h.repository.profiles.value.first().displayName)
            assertEquals(latest, h.draft.base); assertEquals("1400", h.draft.rawMtu)
            assertEquals(1L, h.sessions.state.value.applied!!.saved.revision); assertTrue(h.sessions.requests.isEmpty())
        }
    }
    /** 保存期间继续输入非法值并切换标签，真实 ViewModel 回执只推进基准，保留新原文、错误和模式。 */
    @Test fun lateSave_keepsNewInputAndMode() = runTest {
        val memory = repository(); val gate = CompletableDeferred<Unit>()
        val delayed = object : ProfileRepository by memory {
            override suspend fun save(profileId: LocalProfileId, document: CoreConfigDocument, policy: AndroidVpnPolicy, expectedRevision: Long): WriteResult<SavedRevision> {
                gate.await(); return memory.save(profileId, document, policy, expectedRevision)
            }
        }
        withConfiguration(delayed, sessions()) { h ->
            h.edit(DraftField.Mtu, "1400"); h.act(ConfigurationAction.Save)
            h.edit(DraftField.Mtu, "still-editing"); h.act(ConfigurationAction.ChangeMode(EditorMode.Full))
            gate.complete(Unit); runCurrent()
            assertEquals(1400, memory.findSaved(savedA.profileId)!!.core.mtu)
            assertEquals(2L, h.draft.base.revision); assertEquals("still-editing", h.draft.rawMtu)
            assertTrue(DraftField.Mtu in h.draft.errors); assertTrue(h.draft.dirty)
            assertEquals(EditorMode.Full, h.draft.mode); assertTrue(h.sessions.requests.isEmpty())
        }
    }
}
