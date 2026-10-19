package com.github.zzwtsy.chuanmen

import androidx.lifecycle.ViewModelStore
import com.github.zzwtsy.chuanmen.features.configuration.ConfigurationAction
import com.github.zzwtsy.chuanmen.features.configuration.ConfigurationViewModel
import com.github.zzwtsy.chuanmen.features.configuration.lib.DraftField
import com.github.zzwtsy.chuanmen.features.configuration.lib.EditorMode
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.MemoryProfileRepository
import com.github.zzwtsy.chuanmen.lib.profile.ProfileSummary
import com.github.zzwtsy.chuanmen.lib.vpn.ConnectionPhase
import com.github.zzwtsy.chuanmen.lib.vpn.RecordingSessions
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationBehaviorTest {
    private val profileB = LocalProfileId("00000000-0000-4000-8000-000000000002")

    /** A 正在运行时查看并编辑 B，再切到日志与返回配置，断言 A 会话不变且 B 的 raw/error/模式保留。 */
    @Test
    fun viewingAndNavigating_preserveSessionAndDraft() = runTest {
        withModel { model, configuration, engine ->
            val original = engine.state.value
            model.act(AppAction.ViewProfile(profileB))
            configuration.act(ConfigurationAction.Edit(DraftField.Mtu, "invalid"))
            configuration.act(ConfigurationAction.ChangeMode(EditorMode.Full))
            model.act(AppAction.Navigate(Destination.Logs))
            model.act(AppAction.Navigate(Destination.Configuration))
            runCurrent()
            assertEquals(profileB, model.state.value.navigation.viewedProfileId)
            assertEquals(original, engine.state.value)
            assertEquals("invalid", configuration.state.value.draft!!.rawMtu)
            assertEquals(EditorMode.Full, configuration.state.value.draft!!.mode)
            assertTrue(DraftField.Mtu in configuration.state.value.draft!!.errors)
            assertTrue(engine.requests.isEmpty())
        }
    }

    /** 编辑 A 后打开设置再返回，断言设置关闭、导航仍指向配置，且未保存输入没有丢失。 */
    @Test
    fun settingsBack_preservesCurrentEditor() = runTest {
        withModel { model, configuration, engine ->
            model.act(AppAction.Navigate(Destination.Configuration))
            configuration.act(ConfigurationAction.Edit(DraftField.NetworkName, "unsaved-network"))
            model.act(AppAction.OpenSettings)
            runCurrent()
            assertTrue(model.state.value.navigation.settingsOpen)
            model.act(AppAction.Back)
            runCurrent()
            assertFalse(model.state.value.navigation.settingsOpen)
            assertEquals(Destination.Configuration, model.state.value.navigation.destination)
            assertEquals("unsaved-network", configuration.state.value.draft!!.rawNetworkName)
            assertTrue(configuration.state.value.draft!!.dirty)
            assertTrue(engine.requests.isEmpty())
        }
    }

    /**
     * A 正在运行且已本地改名时保存新 MTU，再恢复运行配置；断言待应用从真变假、修订递增，
     * 本地名称和原 applied 保留、草稿恢复原 MTU，整个流程不提交引擎命令。
     */
    @Test
    fun restoreApplied_clearsPendingWithoutChangingSession() = runTest {
        withModel { model, configuration, engine ->
            val originalSession = engine.state.value
            val originalSaved = configuration.state.value.profile!!.saved
            configuration.act(ConfigurationAction.Rename("家里的网络"))
            model.act(AppAction.Navigate(Destination.Configuration))
            configuration.act(ConfigurationAction.Edit(DraftField.Mtu, "1400"))
            configuration.act(ConfigurationAction.Save)
            runCurrent()

            val changedSaved = configuration.state.value.profile!!.saved
            assertTrue(configuration.state.value.pendingApplication)
            assertEquals(1400, changedSaved.core.mtu)
            assertEquals(originalSaved.revision + 1, changedSaved.revision)
            assertEquals(originalSession, engine.state.value)
            assertTrue(engine.requests.isEmpty())

            configuration.act(ConfigurationAction.Restore)
            runCurrent()

            val restored = configuration.state.value
            assertFalse(restored.pendingApplication)
            assertEquals(changedSaved.revision + 1, restored.profile!!.saved.revision)
            assertEquals(originalSession.applied!!.saved.core, restored.profile!!.saved.core)
            assertEquals(originalSession.applied!!.saved.vpnPolicy, restored.profile!!.saved.vpnPolicy)
            assertEquals("家里的网络", restored.profile!!.displayName)
            assertEquals(originalSession.applied, engine.state.value.applied)
            assertEquals(originalSession, engine.state.value)
            assertEquals(originalSaved.core.mtu.toString(), restored.draft!!.rawMtu)
            assertFalse(restored.draft.dirty)
            assertTrue(engine.requests.isEmpty())
        }
    }

    private fun TestScope.withModel(check: TestScope.(ChuanmenViewModel, ConfigurationViewModel, RecordingSessions) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val saved = testSavedRevision()
            val applied = testAppliedSnapshot(saved)
            val engine = RecordingSessions(SessionState(
                phase = ConnectionPhase.Running, sessionId = applied.sessionId,
                requestedProfileId = saved.profileId, applied = applied,
            ))
            val repository = MemoryProfileRepository(listOf(
                ProfileSummary(saved, "网络 A"), ProfileSummary(testSavedRevision(profileB), "网络 B"),
            ))
            val model = ChuanmenViewModel(repository.profiles, engine.state)
            store.put("test", model)
            val configuration = ConfigurationViewModel(repository, engine, model.viewedProfileId)
            store.put("configuration", configuration)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { configuration.state.collect {} }
            // 保持订阅以启动 WhileSubscribed 的状态组合；测试结束由 backgroundScope 取消收集。
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
            runCurrent()
            check(model, configuration, engine)
        } finally {
            store.clear()
            // 先执行 ViewModel 取消后的清理，再撤销 Main 替身，避免清理任务落入 Android Looper。
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
