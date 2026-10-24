package com.github.zzwtsy.chuanmen.lib.logging

import com.github.zzwtsy.chuanmen.lib.vpn.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogCollectorTest {
    private fun emit(logs: LogCollector) = logs.emit(LogSource.App, LogLevel.Info, LogModule.Session, LogCode.Start)

    /** 启动读取旧文件期间仍采集新记录；合并超过 2000 条时保留新尾部并登记被淘汰的旧记录。 */
    @Test fun archiveLoadingDoesNotHideConcurrentMemoryEviction() = runTest {
        val release = CompletableDeferred<Unit>()
        val archiveId = "00000000-0000-4000-8000-000000000001"
        val persistence = object : LogPersistence {
            override fun prepare() {}
            override fun append(record: LogRecord) {}
            override suspend fun read(consume: suspend (LogRecord) -> Unit) {
                release.await()
                repeat(2000) { index -> consume(LogRecord(archiveId, index + 1L, 0, 0,
                    LogSource.App, LogLevel.Info, LogModule.Session, LogCode.Start)) }
            }
        }
        val logs = LogCollector(backgroundScope, persistence, MonotonicClock { 0 }, io = StandardTestDispatcher(testScheduler))
        runCurrent()
        repeat(5) { emit(logs) }
        release.complete(Unit); runCurrent()
        assertEquals(2000, logs.state.value.records.size)
        assertEquals(5L, logs.state.value.dropped)
        assertEquals(6L, logs.state.value.records.first().sequence)
        assertTrue(logs.state.value.records.takeLast(5).all { it.fileId != archiveId })
    }

    /** 第 2001 条到达时内存只保留最近 2000 条并增加一次淘汰计数，磁盘写入不依赖页面订阅。 */
    @Test fun boundedMemoryAndWriterAreIndependentOfUi() = runTest {
        val persistence = TestLogPersistence()
        val logs = LogCollector(backgroundScope, persistence, MonotonicClock { testScheduler.currentTime }, io = StandardTestDispatcher(testScheduler))
        runCurrent()
        repeat(2001) { emit(logs); if (it % 500 == 0) runCurrent() }; runCurrent()
        assertEquals(2000, logs.state.value.records.size)
        assertEquals(2L, logs.state.value.records.first().sequence)
        assertEquals(2001L, logs.state.value.records.last().sequence)
        assertEquals(1L, logs.state.value.dropped)
        assertEquals(2001, persistence.records.size)
    }

    /** 写盘失败持续显示 memory-only；继续收集但不无限补写，明确重试后仅持久化新记录。 */
    @Test fun diskFailureDoesNotStopCollectionAndRetryDoesNotReplayBacklog() = runTest {
        val persistence = TestLogPersistence()
        val logs = LogCollector(backgroundScope, persistence, MonotonicClock { testScheduler.currentTime }, io = StandardTestDispatcher(testScheduler))
        runCurrent(); persistence.fail = true
        emit(logs); runCurrent()
        assertTrue(logs.state.value.persistenceError)
        repeat(5) { emit(logs) }; runCurrent()
        assertEquals(6, logs.state.value.records.size)
        assertTrue(persistence.records.isEmpty())
        persistence.fail = false; logs.retryPersistence(); runCurrent()
        assertFalse(logs.state.value.persistenceError)
        emit(logs); runCurrent()
        assertEquals(1, persistence.records.size)
        assertEquals(7L, persistence.records.single().sequence)
    }

    /** 默认忽略 Debug，只有显式开启并收到 native 确认后才采集；级别筛选不会触发该调用。 */
    @Test fun debugCollectionRequiresExplicitSuccessfulNativeChange() = runTest {
        val calls = mutableListOf<Boolean>()
        val logs = LogCollector(backgroundScope, TestLogPersistence(), MonotonicClock { 0 }, io = StandardTestDispatcher(testScheduler), setNativeDebug = { calls += it; true })
        runCurrent()
        fun debug() = logs.emit(LogSource.EasyTier, LogLevel.Debug, LogModule.Core, LogCode.CoreDebug)
        debug(); assertTrue(logs.state.value.records.isEmpty())
        logs.setDebugCollection(true); runCurrent(); debug(); runCurrent()
        assertEquals(listOf(true), calls)
        assertEquals(1, logs.state.value.records.size)
        logs.setDebugCollection(false); runCurrent(); debug()
        assertEquals(listOf(true, false), calls)
        assertEquals(1, logs.state.value.records.size)
    }

    /** 任意 code、目标、会话及白名单字段中的合成秘密在缓冲前拒绝，文件与流式导出均不存在秘密。 */
    @Test fun unsafeFieldsCannotReachMemoryDiskOrExport() = runTest {
        val persistence = TestLogPersistence()
        val logs = LogCollector(backgroundScope, persistence, MonotonicClock { 0 }, io = StandardTestDispatcher(testScheduler))
        runCurrent()
        logs.diagnostics(CoreDiagnostics(listOf(CoreDiagnostic(0, "core_process", "INFO", "synthetic-secret", "core_info"),
            CoreDiagnostic(0, "core_process", "INFO", "core", "synthetic-secret")), 0, 128))
        logs.emit(LogSource.App, LogLevel.Info, LogModule.Session, LogCode.Start, fields = mapOf(LogField.Count to "synthetic-secret"))
        logs.emit(LogSource.App, LogLevel.Info, LogModule.Session, LogCode.Start, session = SessionId("synthetic-secret", 1))
        emit(logs); runCurrent()
        val output = ByteArrayOutputStream()
        logs.export(LogFilter(), output)
        assertEquals(4L, logs.state.value.dropped)
        assertEquals(1, logs.state.value.records.size)
        assertFalse(logs.state.value.toString().contains("synthetic-secret"))
        assertFalse(persistence.records.toString().contains("synthetic-secret"))
        assertFalse(output.toString("UTF-8").contains("synthetic-secret"))
        assertEquals(1, output.toString("UTF-8").lineSequence().filter(String::isNotEmpty).count())
    }

    /** 超长安全数字字段进入内存前截断并标记，UTF-8 JSONL 含换行不超过 8 KiB 且可解析。 */
    @Test fun oversizedSafeRecordIsTruncatedBeforeBuffering() = runTest {
        val logs = LogCollector(backgroundScope, TestLogPersistence(), MonotonicClock { 0 }, io = StandardTestDispatcher(testScheduler))
        runCurrent()
        logs.emit(LogSource.App, LogLevel.Info, LogModule.Session, LogCode.Start, fields = LogField.entries.associateWith { "9".repeat(20_000) })
        val record = logs.state.value.records.single()
        assertTrue(record.truncated)
        assertTrue(record.fields.values.all { it.length <= 512 })
        val json = LogJson.encode(record)
        assertTrue(json.size <= 8192)
        assertEquals(record, LogJson.decode(json.toString(Charsets.UTF_8).trimEnd()))
        val injected = com.google.gson.JsonParser.parseString(json.toString(Charsets.UTF_8)).asJsonObject.apply { addProperty("message", "synthetic-secret") }
        assertFalse(LogJson.decode(injected.toString())!!.message.contains("synthetic-secret"))
    }

    /** 导出取消时读取流执行 finally 关闭，正式采集器仍可处理后续记录。 */
    @Test fun cancellingExportClosesReaderWithoutStoppingCollection() = runTest {
        var reading = false
        var closed = false
        var blockRead = false
        val persistence = object : LogPersistence {
            override fun prepare() {}
            override fun append(record: LogRecord) {}
            override suspend fun read(consume: suspend (LogRecord) -> Unit) {
                if (blockRead) {
                    reading = true
                    try { awaitCancellation() } finally { closed = true }
                }
            }
        }
        val logs = LogCollector(backgroundScope, persistence, MonotonicClock { 0 }, io = StandardTestDispatcher(testScheduler))
        runCurrent(); blockRead = true
        val job = backgroundScope.launch { logs.export(LogFilter(), ByteArrayOutputStream()) }
        runCurrent(); assertTrue(reading)
        job.cancel(); runCurrent(); assertTrue(closed)
        emit(logs); runCurrent()
        assertEquals(1, logs.state.value.records.size)
        assertFalse(logs.state.value.persistenceError)
    }
}
