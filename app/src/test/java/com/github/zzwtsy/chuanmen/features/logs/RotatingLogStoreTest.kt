package com.github.zzwtsy.chuanmen.features.logs

import com.github.zzwtsy.chuanmen.lib.logging.*
import com.github.zzwtsy.chuanmen.lib.vpn.MonotonicClock
import java.util.UUID
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class RotatingLogStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun record(id: String = UUID.randomUUID().toString(), sequence: Long = 1) =
        LogRecord(id, sequence, 100, 100, LogSource.App, LogLevel.Info, LogModule.Session, LogCode.Start)

    /** 生产采集器在 2 MiB 前更换日志段；第六段淘汰最早段，五个文件均是有效且不超预算的 JSONL。 */
    @Test fun collectorRotatesBeforeFileBudgetAndKeepsFiveFiles() = runTest {
        val directory = temporary.newFolder()
        var now = 1000L
        val store = RotatingLogStore(directory) { now }
        val logs = LogCollector(backgroundScope, store, MonotonicClock { 100 }, { now }, StandardTestDispatcher(testScheduler))
        runCurrent()
        var firstId: String? = null
        repeat(10_000) { index ->
            logs.emit(LogSource.App, LogLevel.Info, LogModule.Session, LogCode.Start,
                fields = LogField.entries.associateWith { "9".repeat(512) })
            if (firstId == null) firstId = logs.state.value.records.last().fileId
            now++
            if (index % 100 == 0) runCurrent()
        }
        runCurrent()
        assertFalse(logs.state.value.persistenceError)
        val files = directory.listFiles()!!.filter { it.extension == "jsonl" }
        assertEquals(5, files.size)
        assertTrue(files.all { it.length() in 1..LogCollector.MAX_FILE_BYTES.toLong() })
        assertTrue(files.none { it.name.endsWith("-$firstId.jsonl") })
        files.forEach { file -> file.forEachLine { assertNotNull(LogJson.decode(it)) } }
        val output = ByteArrayOutputStream()
        logs.export(LogFilter(keyword = "启动"), output)
        val exported = output.toString("UTF-8").lineSequence().filter(String::isNotEmpty).map { LogJson.decode(it)!! }.toList()
        assertTrue(exported.isNotEmpty())
        assertEquals(exported.size, exported.map { it.id }.distinct().size)
    }

    /** 创建后恰好七天保留，超过一毫秒淘汰；系统时间回拨时不将未来创建时刻判为过期。 */
    @Test fun retentionBoundaryAndClockRollbackAreDeterministic() {
        val directory = temporary.newFolder()
        var now = 1000L
        val store = RotatingLogStore(directory) { now }
        store.prepare(); store.append(record())
        now += RotatingLogStore.RETENTION_MILLIS; store.prepare()
        assertEquals(1, directory.listFiles()!!.size)
        now++; store.prepare()
        assertEquals(0, directory.listFiles()!!.size)
        store.append(record()); now = 1; store.prepare()
        assertEquals(1, directory.listFiles()!!.size)
    }

    /** 回拨后新建第六个文件仍按实际创建顺序淘汰最早段，而不误删回拨后创建的最新段。 */
    @Test fun capacityOrderingDoesNotDependOnWallClock() {
        val directory = temporary.newFolder()
        var now = 1000L
        val store = RotatingLogStore(directory) { now }
        store.prepare()
        val first = record(); store.append(first)
        repeat(4) { now++; store.append(record()) }
        now = 1
        val newest = record(); store.append(newest)
        val files = directory.listFiles()!!
        assertEquals(5, files.size)
        assertFalse(files.any { it.name.endsWith("-${first.fileId}.jsonl") })
        assertTrue(files.any { it.name.endsWith("-${newest.fileId}.jsonl") })
    }

    /** 进程重启后的序号可重新从一开始，不同文件 UUID 的身份不冲突，旧文件仍可逐条查询。 */
    @Test fun restartPreservesFileScopedIdentity() = runTest {
        val directory = temporary.newFolder()
        val one = RotatingLogStore(directory) { 1000 }; one.prepare(); one.append(record())
        val two = RotatingLogStore(directory) { 1001 }; two.prepare(); two.append(record())
        val records = mutableListOf<LogRecord>()
        two.read { records += it }
        assertEquals(listOf(1L, 1L), records.map { it.sequence })
        assertEquals(2, records.map { it.id }.distinct().size)
    }
}
