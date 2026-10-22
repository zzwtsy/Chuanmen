package com.github.zzwtsy.chuanmen.features.configuration

import androidx.lifecycle.ViewModelStore
import com.github.zzwtsy.chuanmen.features.configuration.lib.DraftField
import com.github.zzwtsy.chuanmen.features.configuration.lib.EditorMode
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.MemoryProfileRepository
import com.github.zzwtsy.chuanmen.lib.profile.ProfileSummary
import com.github.zzwtsy.chuanmen.lib.vpn.OperationKind
import com.github.zzwtsy.chuanmen.lib.vpn.RecordingSessions
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import com.github.zzwtsy.chuanmen.testSavedRevision
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfigurationViewModelTest {
    /** 切换身份后立即编辑，即使展示流尚未更新也只修改新对象，返回 A 的草稿保持原值。 */
    @Test fun immediateAction_usesCurrentIdentityInsteadOfPreviousProjection() = runTest {
        withModel { model, viewed, repository, _ ->
            val first = viewed.value!!
            val second = repository.profiles.value.last().saved.profileId
            viewed.value = second
            model.act(ConfigurationAction.Edit(DraftField.Mtu, "invalid"))
            model.act(ConfigurationAction.ChangeMode(EditorMode.Full))
            runCurrent()
            assertEquals(second, model.state.value.profile!!.saved.profileId)
            assertEquals("invalid", model.state.value.draft!!.rawMtu)
            assertTrue(DraftField.Mtu in model.state.value.draft!!.errors)
            viewed.value = first
            runCurrent()
            assertEquals("1380", model.state.value.draft!!.rawMtu)
            viewed.value = second
            runCurrent()
            assertEquals("invalid", model.state.value.draft!!.rawMtu)
            assertEquals(EditorMode.Full, model.state.value.draft!!.mode)
        }
    }

    /** 仅保存更新唯一仓库，未提交会话动作；显式应用被拒绝后保留反馈与保存内容，换对象清理旧反馈。 */
    @Test fun saveAndRejectedApply_keepSourcesAndFeedbackSeparate() = runTest {
        withModel { model, viewed, repository, sessions ->
            model.act(ConfigurationAction.Edit(DraftField.Mtu, "1400"))
            model.act(ConfigurationAction.Save)
            runCurrent()
            assertEquals(1400, repository.findSaved(viewed.value!!)!!.core.mtu)
            assertTrue(sessions.requests.isEmpty())
            model.act(ConfigurationAction.Apply)
            runCurrent()
            assertEquals(listOf(OperationKind.Apply), sessions.requests)
            val rejected = model.state.value.message
            assertTrue(rejected!!.startsWith("操作未执行："))
            model.act(ConfigurationAction.ChangeMode(EditorMode.Full))
            runCurrent()
            assertEquals(rejected, model.state.value.message)
            assertEquals(1400, model.state.value.profile!!.saved.core.mtu)
            viewed.value = repository.profiles.value.last().saved.profileId
            runCurrent()
            assertNull(model.state.value.message)
        }
    }

    private fun TestScope.withModel(check: TestScope.(ConfigurationViewModel, MutableStateFlow<LocalProfileId?>, MemoryProfileRepository, RecordingSessions) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val first = testSavedRevision()
            val second = testSavedRevision(LocalProfileId("00000000-0000-4000-8000-000000000002"))
            val repository = MemoryProfileRepository(listOf(ProfileSummary(first, "A"), ProfileSummary(second, "B")))
            val viewed = MutableStateFlow<LocalProfileId?>(first.profileId)
            val sessions = RecordingSessions(SessionState())
            val model = ConfigurationViewModel(repository, sessions, viewed)
            store.put("configuration", model)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
            runCurrent()
            check(model, viewed, repository, sessions)
        } finally {
            store.clear()
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
