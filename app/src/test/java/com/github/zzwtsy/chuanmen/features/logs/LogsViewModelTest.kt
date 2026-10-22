package com.github.zzwtsy.chuanmen.features.logs

import androidx.lifecycle.ViewModelStore
import com.github.zzwtsy.chuanmen.lib.vpn.CoreDiagnostic
import com.github.zzwtsy.chuanmen.lib.vpn.CoreDiagnostics
import com.github.zzwtsy.chuanmen.lib.vpn.LifecycleDiagnostic
import com.github.zzwtsy.chuanmen.lib.vpn.OperationToken
import com.github.zzwtsy.chuanmen.lib.vpn.SessionId
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelTest {
    /** 两类真实记录保留各自输入顺序和原有格式；多次订阅不生成日志，来源清空后展示空态。 */
    @Test fun records_keepTheirSourcesOrderAndFormatWithoutSubscriptionSideEffects() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val token = OperationToken(SessionId("test-log-session", 7), 3)
            val original = SessionState(
                lifecycle = listOf(LifecycleDiagnostic(5, token, 9, "Started"), LifecycleDiagnostic(1, token, 9, "Stopped")),
                coreDiagnostics = CoreDiagnostics(listOf(
                    CoreDiagnostic(10, "process", "WARN", "session", "RuntimeEvent"),
                    CoreDiagnostic(0, "process", "INFO", "tun", "Attached"),
                ), 0, 100),
            )
            val sessions = MutableStateFlow(original)
            val model = LogsViewModel(sessions)
            store.put("logs", model)
            val expected = listOf(
                "+5 ms · 会话 7 · 操作 3 · 修订 9 · Started",
                "+1 ms · 会话 7 · 操作 3 · 修订 9 · Stopped",
                "+10 ms · 核心进程 · WARN · session · RuntimeEvent",
                "+0 ms · 核心进程 · INFO · tun · Attached",
            )
            assertEquals(expected, model.state.value.lines)
            repeat(2) { backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} } }
            runCurrent()
            assertEquals(expected, model.state.value.lines)
            assertEquals(original, sessions.value)
            sessions.value = SessionState()
            runCurrent()
            assertTrue(model.state.value.lines.isEmpty())
        } finally {
            store.clear(); runCurrent(); Dispatchers.resetMain()
        }
    }
}
