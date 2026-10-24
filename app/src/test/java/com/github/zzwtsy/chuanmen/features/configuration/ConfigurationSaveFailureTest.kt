package com.github.zzwtsy.chuanmen.features.configuration

import com.github.zzwtsy.chuanmen.features.configuration.lib.DraftField
import com.github.zzwtsy.chuanmen.features.configuration.lib.EditorMode
import com.github.zzwtsy.chuanmen.lib.profile.*
import com.github.zzwtsy.chuanmen.testSavedRevision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationSaveFailureTest {
    /** 写盘失败后清除短消息并往返查看配置，草稿和持续错误仍在；成功重试才清错且不应用运行配置。 */
    @Test fun failedSave_survivesNavigationAndClearsOnlyAfterSuccessfulRetry() = runTest {
        val a = testSavedRevision()
        val b = testSavedRevision(LocalProfileId("00000000-0000-4000-8000-000000000002"))
        val memory = MemoryProfileRepository(listOf(ProfileSummary(a, "A"), ProfileSummary(b, "B")))
        var fail = true
        val repository = object : ProfileRepository by memory {
            override suspend fun save(profileId: LocalProfileId, document: CoreConfigDocument, policy: AndroidVpnPolicy, expectedRevision: Long): WriteResult<SavedRevision> =
                if (fail) WriteResult.Failure(WriteError.WriteFailed) else memory.save(profileId, document, policy, expectedRevision)
        }
        withConfiguration(repository) { h ->
            h.edit(DraftField.Mtu, "1400")
            h.act(ConfigurationAction.Save)
            val failure = requireNotNull(h.draft.saveFailure)
            h.act(ConfigurationAction.ClearMessage)
            h.act(ConfigurationAction.ChangeMode(EditorMode.Full))
            h.view(b.profileId)
            assertNull(h.draft.saveFailure)
            h.view(a.profileId)
            assertEquals(failure, h.draft.saveFailure)
            assertEquals("1400", h.draft.rawMtu)
            assertTrue(h.draft.dirty)
            assertNull(h.model.state.value.message)
            assertEquals(a, memory.findSaved(a.profileId))
            fail = false
            h.act(ConfigurationAction.Save)
            assertNull(h.draft.saveFailure)
            assertFalse(h.draft.dirty)
            assertEquals(1400, memory.findSaved(a.profileId)!!.core.mtu)
            assertTrue(h.sessions.requests.isEmpty())
        }
    }
}
