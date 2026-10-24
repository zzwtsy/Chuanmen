package com.github.zzwtsy.chuanmen.features.logs

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import com.github.zzwtsy.chuanmen.*
import com.github.zzwtsy.chuanmen.lib.logging.*
import com.github.zzwtsy.chuanmen.lib.vpn.*
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LogsReadingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun record(index: Int) = LogRecord("00000000-0000-4000-8000-000000000001", index + 1L, 1000, 1000,
        LogSource.App, LogLevel.Info, LogModule.Session, LogCode.Start, fields = mapOf(LogField.Count to index.toString()))

    /** 非空双来源日志经真实 ViewModel 连续筛选和输入后仍保留焦点；实时追加只增加提示，不滚走输入。 */
    @Test fun sourceFilterKeepsControlsVisible() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ViewModelStore()
        val persistence = object : LogPersistence {
            override fun prepare() {}
            override fun append(record: LogRecord) {}
            override suspend fun read(consume: suspend (LogRecord) -> Unit) {}
        }
        try {
            val logs = LogCollector(scope, persistence, MonotonicClock { 0 })
            val session = SessionId("00000000-0000-4000-8000-000000000001", 7)
            repeat(40) { index ->
                if (index % 2 == 0) logs.lifecycle(LifecycleDiagnostic(0, OperationToken(session, index + 1L), 1, "command_start"))
                else logs.diagnostics(CoreDiagnostics(listOf(CoreDiagnostic(0, "core_process", "WARN", "peer", "peer_connect_failed")), 0, 128))
            }
            val model = LogsViewModel(logs).also { store.put("logs", it) }
            compose.setContent {
                ChuanmenScreen(AppUiState(navigation = AppNavigation(destination = Destination.Logs)), {}) { _, _ -> LogsRoute(model) }
            }
            compose.waitUntil(5000) { model.state.value.records.size == 40 }
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(0)
            compose.onNode(hasText("App") and hasClickAction()).performClick()
            compose.waitForIdle()
            assertEquals(20, model.state.value.records.size)
            val input = compose.onNode(hasText("搜索安全日志消息") and hasSetTextAction())
            input.assertIsDisplayed().performClick().performTextInput("开始")
            input.assertIsFocused().assertIsDisplayed()
            compose.onNode(hasText("会话 7") and hasClickAction()).performClick()
            compose.waitForIdle()
            assertEquals(session.value, model.state.value.filter.sessionId)
            input.performClick()
            logs.lifecycle(LifecycleDiagnostic(0, OperationToken(session, 99), 1, "command_start"))
            compose.waitUntil(5000) { model.state.value.newRecords == 1 }
            input.assertIsFocused().assertIsDisplayed()
            assertFalse(model.state.value.following)
            assertFalse(model.state.value.anchorEvicted)
            compose.onNodeWithText("1 条新记录 · 回到底部").assertIsDisplayed()
        } finally { store.clear(); scope.cancel() }
    }

    /** 真实 LazyColumn 在暂停阅读后接收新记录不跳动；点击提示跟随末尾，锚点淘汰后定位现存最早记录。 */
    @Test fun newRecordsKeepViewportUntilExplicitFollow() {
        var state by mutableStateOf(LogsUiState(records = (0 until 40).map(::record), reading = LogReadingState.Reading(null, null)))
        compose.setContent {
            ChuanmenScreen(AppUiState(navigation = AppNavigation(destination = Destination.Logs)), {}) { _, _ ->
                LogsScreen(state) { action ->
                    when (action) {
                        LogAction.Follow -> state = state.copy(reading = LogReadingState.Following, newRecords = 0)
                        is LogAction.PauseAt -> state = state.copy(reading = LogReadingState.Reading(action.recordId, null))
                        else -> Unit
                    }
                }
            }
        }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).performScrollToIndex(21)
        val message = record(20).message
        compose.onNodeWithText(message).assertIsDisplayed()
        val bounds = compose.onNodeWithText(message).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { state = state.copy(records = state.records + record(40), newRecords = 1) }
        assertEquals(bounds, compose.onNodeWithText(message).fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("1 条新记录 · 回到底部").performClick()
        compose.onNodeWithText(record(40).message).assertIsDisplayed()
        compose.runOnIdle { state = state.copy(records = state.records.takeLast(5),
            reading = LogReadingState.Evicted(record(36).id, null)) }
        compose.onNodeWithText("旧记录已清理，已定位现存最早记录").assertIsDisplayed()
        compose.onNodeWithText(record(36).message).assertIsDisplayed()
    }
}
