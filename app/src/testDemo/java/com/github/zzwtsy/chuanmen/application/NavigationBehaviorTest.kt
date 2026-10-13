package com.github.zzwtsy.chuanmen.application

import androidx.lifecycle.ViewModelStore
import com.github.zzwtsy.chuanmen.application.demo.DemoFixtures
import com.github.zzwtsy.chuanmen.application.demo.FakeEngine
import com.github.zzwtsy.chuanmen.application.demo.ScheduledEngineEvents
import com.github.zzwtsy.chuanmen.application.demo.DemoSessionController
import com.github.zzwtsy.chuanmen.contracts.MonotonicClock
import com.github.zzwtsy.chuanmen.feature.configuration.edit.DraftField
import com.github.zzwtsy.chuanmen.feature.configuration.edit.EditorMode
import com.github.zzwtsy.chuanmen.feature.configuration.storage.DemoProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationBehaviorTest {
    /** A 正在运行时查看并编辑 B，再切到日志与返回配置，断言 A 会话不变且 B 的 raw/error/模式保留。 */
    @Test
    fun viewingAndNavigating_preserveSessionAndDraft() = runTest {
        withModel { model, engine ->
            val original = model.state.value.session
            model.act(AppAction.ViewProfile(DemoFixtures.profileB))
            model.act(AppAction.Edit(DraftField.Mtu, "invalid"))
            model.act(AppAction.ChangeEditorMode(EditorMode.Full))
            model.act(AppAction.Navigate(Destination.Logs))
            model.act(AppAction.Navigate(Destination.Configuration))
            runCurrent()
            assertEquals(DemoFixtures.profileB, model.state.value.navigation.viewedProfileId)
            assertEquals(original, model.state.value.session)
            assertEquals("invalid", model.state.value.draft!!.rawMtu)
            assertEquals(EditorMode.Full, model.state.value.draft!!.mode)
            assertTrue(DraftField.Mtu in model.state.value.draft!!.errors)
            assertTrue(engine.submittedCommands.isEmpty())
        }
    }

    /** 编辑 A 后打开设置再返回，断言设置关闭、导航仍指向配置，且未保存输入没有丢失。 */
    @Test
    fun settingsBack_preservesCurrentEditor() = runTest {
        withModel { model, engine ->
            model.act(AppAction.Navigate(Destination.Configuration))
            model.act(AppAction.Edit(DraftField.NetworkName, "unsaved-network"))
            model.act(AppAction.OpenSettings)
            runCurrent()
            assertTrue(model.state.value.navigation.settingsOpen)
            model.act(AppAction.Back)
            runCurrent()
            assertFalse(model.state.value.navigation.settingsOpen)
            assertEquals(Destination.Configuration, model.state.value.navigation.destination)
            assertEquals("unsaved-network", model.state.value.draft!!.rawNetworkName)
            assertTrue(model.state.value.draft!!.dirty)
            assertTrue(engine.submittedCommands.isEmpty())
        }
    }

    /**
     * A 正在运行且已本地改名时保存新 MTU，再恢复运行配置；断言待应用从真变假、修订递增，
     * 本地名称和原 applied 保留、草稿恢复原 MTU，整个流程不提交引擎命令。
     */
    @Test
    fun restoreApplied_clearsPendingWithoutChangingSession() = runTest {
        withModel { model, engine ->
            val originalSession = model.state.value.session
            val originalSaved = model.state.value.viewed!!.saved
            model.act(AppAction.Rename("家里的网络"))
            model.act(AppAction.Navigate(Destination.Configuration))
            model.act(AppAction.Edit(DraftField.Mtu, "1400"))
            model.act(AppAction.Save)
            runCurrent()

            val changedSaved = model.state.value.viewed!!.saved
            assertTrue(model.state.value.pendingApplication)
            assertEquals(1400, changedSaved.core.mtu)
            assertEquals(originalSaved.revision + 1, changedSaved.revision)
            assertEquals(originalSession, model.state.value.session)
            assertTrue(engine.submittedCommands.isEmpty())

            model.act(AppAction.RestoreApplied)
            runCurrent()

            val restored = model.state.value
            assertFalse(restored.pendingApplication)
            assertEquals(changedSaved.revision + 1, restored.viewed!!.saved.revision)
            assertEquals(originalSession.applied!!.saved.core, restored.viewed!!.saved.core)
            assertEquals(originalSession.applied!!.saved.vpnPolicy, restored.viewed!!.saved.vpnPolicy)
            assertEquals("家里的网络", restored.viewed!!.displayName)
            assertEquals(originalSession.applied, restored.session.applied)
            assertEquals(originalSession, restored.session)
            assertEquals(originalSaved.core.mtu.toString(), restored.draft!!.rawMtu)
            assertFalse(restored.draft.dirty)
            assertTrue(engine.submittedCommands.isEmpty())
        }
    }

    private fun TestScope.withModel(check: TestScope.(ChuanmenViewModel, FakeEngine) -> Unit) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val fixture = DemoFixtures.connected
            lateinit var engine: FakeEngine
            val model = ChuanmenViewModel { scope ->
                engine = FakeEngine(MonotonicClock { testScheduler.currentTime }, ScheduledEngineEvents(scope), fixture.script)
                AppDependencies(DemoProfileRepository(fixture.profiles), DemoSessionController(engine, scope, fixture.initialSession), AppReadings())
            }
            store.put("test", model)
            // 保持订阅以启动 WhileSubscribed 的状态组合；测试结束由 backgroundScope 取消收集。
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
            runCurrent()
            check(model, engine)
        } finally {
            store.clear()
            // 先执行 ViewModel 取消后的清理，再撤销 Main 替身，避免清理任务落入 Android Looper。
            runCurrent()
            Dispatchers.resetMain()
        }
    }
}
