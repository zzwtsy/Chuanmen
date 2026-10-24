package com.github.zzwtsy.chuanmen.features.logs

import androidx.lifecycle.ViewModelStore
import com.github.zzwtsy.chuanmen.lib.logging.*
import com.github.zzwtsy.chuanmen.lib.vpn.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelTest {
    /** 页面取消订阅时保留阅读缓存且不投影新日志；重新订阅后读最新缓冲，保留筛选和暂停边界。 */
    @Test fun hiddenPageRetainsReadingAndResumesFromLatestBuffer() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val logs = LogCollector(backgroundScope, TestLogPersistence(), MonotonicClock { 0 }, io = StandardTestDispatcher(testScheduler))
            val model = LogsViewModel(logs); store.put("logs", model)
            val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
            runCurrent()
            logs.emit(LogSource.App, LogLevel.Info, LogModule.Session, LogCode.Start); runCurrent()
            model.act(LogAction.Filter(LogFilter(keyword = "开始")))
            subscription.cancel(); runCurrent()
            logs.emit(LogSource.App, LogLevel.Info, LogModule.Session, LogCode.Start); runCurrent()
            assertEquals(2, logs.state.value.records.size)
            assertEquals(1, model.state.value.records.size)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }; runCurrent()
            assertEquals(2, model.state.value.records.size)
            assertEquals("开始", model.state.value.filter.keyword)
            assertEquals(1, model.state.value.newRecords)
            assertFalse(model.state.value.following)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    /** 筛选隐藏阅读锚点和暂停边界时不算淘汰，也不把既有结果算成新日志；追加匹配事件才增加计数。 */
    @Test fun filteringPreservesReadingBoundaryWithoutFalseEviction() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val logs = LogCollector(backgroundScope, TestLogPersistence(), MonotonicClock { 0 }, io = StandardTestDispatcher(testScheduler))
            val model = LogsViewModel(logs); store.put("logs", model)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
            runCurrent()
            logs.emit(LogSource.App, LogLevel.Info, LogModule.Session, LogCode.Start)
            logs.diagnostics(CoreDiagnostics(listOf(CoreDiagnostic(0, "core_process", "WARN", "peer", "peer_connect_failed")), 0, 128))
            runCurrent()
            val anchor = model.state.value.records.first().id
            model.act(LogAction.PauseAt(anchor))
            model.act(LogAction.Filter(LogFilter(source = LogSource.EasyTier)))
            assertFalse(model.state.value.following)
            assertFalse(model.state.value.anchorEvicted)
            assertEquals(0, model.state.value.newRecords)
            assertEquals(1, model.state.value.records.size)
            model.act(LogAction.Filter(LogFilter(source = LogSource.App)))
            assertEquals(0, model.state.value.newRecords)
            logs.emit(LogSource.App, LogLevel.Info, LogModule.Session, LogCode.Start); runCurrent()
            assertEquals(1, model.state.value.newRecords)
            assertFalse(model.state.value.following)
            model.act(LogAction.Follow)
            assertTrue(model.state.value.following)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    /** 两个来源由同一采集器排序，中文消息可读；重订阅不生成或清空记录，筛选不更改采集等级。 */
    @Test fun records_keepTheirSourcesOrderAndFormatWithoutSubscriptionSideEffects() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val persistence = TestLogPersistence()
            var debugCalls = 0
            val logs = LogCollector(backgroundScope, persistence, MonotonicClock { testScheduler.currentTime },
                io = StandardTestDispatcher(testScheduler), setNativeDebug = { debugCalls++; true })
            runCurrent()
            val token = OperationToken(SessionId("00000000-0000-4000-8000-000000000001", 7), 3)
            logs.lifecycle(LifecycleDiagnostic(5, token, 9, "receipt_core_started"))
            logs.diagnostics(CoreDiagnostics(listOf(CoreDiagnostic(10, "core_process", "WARN", "tunnel", "core_warn")), 0, 128))
            val model = LogsViewModel(logs); store.put("logs", model)
            val subscription = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
            runCurrent()
            assertEquals(listOf(LogSource.App, LogSource.EasyTier), model.state.value.records.map { it.source })
            assertEquals("核心已启动 · 操作 3 · 修订 9", model.state.value.records.first().message)
            assertNull(model.state.value.records.last().sessionId)
            subscription.cancel(); runCurrent()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }; runCurrent()
            assertEquals(2, model.state.value.records.size)
            model.act(LogAction.Filter(LogFilter(LogSource.EasyTier, LogLevel.Warn, "警告"))); runCurrent()
            assertEquals(1, model.state.value.records.size)
            model.act(LogAction.Filter(LogFilter(minimumLevel = LogLevel.Debug, sessionId = token.sessionId.value))); runCurrent()
            assertEquals(1, model.state.value.records.size)
            assertEquals(0, debugCalls)
            assertFalse(logs.state.value.debugEnabled)
            assertEquals(2, persistence.records.size)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    /** 上滑暂停后保留稳定锚点并计数新记录；锚点淘汰提示清理，显式点击才恢复跟随。像素偏移由 UI 回归验证。 */
    @Test fun pausedReadingPreservesIdentityAndReportsEviction() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val logs = LogCollector(backgroundScope, TestLogPersistence(), MonotonicClock { testScheduler.currentTime }, io = StandardTestDispatcher(testScheduler))
            val model = LogsViewModel(logs); store.put("logs", model)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }; runCurrent()
            fun emit() { logs.emit(LogSource.App, LogLevel.Info, LogModule.Session, LogCode.Start) }
            repeat(3) { emit() }; runCurrent()
            val anchor = model.state.value.records[1].id
            model.act(LogAction.PauseAt(anchor)); runCurrent()
            emit(); runCurrent()
            assertEquals(anchor, model.state.value.anchorId)
            assertTrue(model.state.value.reading is LogReadingState.Reading)
            assertEquals(1, model.state.value.newRecords)
            assertFalse(model.state.value.following)
            repeat(2000) { emit(); if (it % 500 == 0) runCurrent() }; runCurrent()
            assertTrue(model.state.value.anchorEvicted)
            assertEquals(model.state.value.records.first().id, model.state.value.anchorId)
            model.act(LogAction.Follow); runCurrent()
            assertTrue(model.state.value.following)
            assertEquals(0, model.state.value.newRecords)
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }
}
