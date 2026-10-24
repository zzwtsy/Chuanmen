package com.github.zzwtsy.chuanmen.lib.logging

import com.github.zzwtsy.chuanmen.lib.vpn.*
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class LogBuffer(
    val records: List<LogRecord> = emptyList(), val dropped: Long = 0,
    val persistenceError: Boolean = false, val debugEnabled: Boolean = false, val debugError: Boolean = false,
)

/** 服务侧统一汇聚；生产者只更新有界内存并 trySend，绝不等待磁盘。 */
class LogCollector(
    scope: CoroutineScope,
    private val persistence: LogPersistence,
    private val clock: MonotonicClock,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val setNativeDebug: suspend (Boolean) -> Boolean = { false },
) {
    private sealed interface Write {
        data class Record(val id: String) : Write
        data object Retry : Write
        data class Debug(val enabled: Boolean) : Write
    }
    private val lock = Any()
    private val pending = Channel<Write>(2000)
    private val mutableState = MutableStateFlow(LogBuffer())
    val state: StateFlow<LogBuffer> = mutableState
    private var sequence = 0L
    private var fileId = UUID.randomUUID().toString()
    private var segmentBytes = 0
    private var coreDropped = 0L

    init {
        scope.launch(io) {
            try {
                persistence.prepare()
                val archive = ArrayDeque<LogRecord>()
                persistence.read { record ->
                    archive.add(record)
                    if (archive.size > 2000) archive.removeFirst()
                }
                synchronized(lock) {
                    val current = state.value
                    val merged = (archive + current.records).distinctBy { it.id }
                    mutableState.value = current.copy(records = merged.takeLast(2000),
                        dropped = current.dropped + (merged.size - 2000).coerceAtLeast(0))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { diskFailed() }
            for (command in pending) {
                when (command) {
                    is Write.Record -> if (!state.value.persistenceError) {
                        val record = synchronized(lock) { state.value.records.firstOrNull { it.id == command.id } }
                        try { if (record != null) persistence.append(record) }
                        catch (_: Exception) { diskFailed() }
                    }
                    Write.Retry -> {
                        try {
                            persistence.prepare()
                            synchronized(lock) {
                                fileId = UUID.randomUUID().toString(); segmentBytes = 0
                                mutableState.value = state.value.copy(persistenceError = false)
                            }
                        } catch (_: Exception) { diskFailed() }
                    }
                    is Write.Debug -> {
                        val success = try { setNativeDebug(command.enabled) } catch (_: Exception) { false }
                        synchronized(lock) {
                            mutableState.value = state.value.copy(debugEnabled = if (success) command.enabled else state.value.debugEnabled, debugError = !success)
                        }
                    }
                }
            }
        }
    }

    fun retryPersistence() { if (pending.trySend(Write.Retry).isFailure) discarded() }
    fun setDebugCollection(enabled: Boolean) { if (pending.trySend(Write.Debug(enabled)).isFailure) discarded() }

    fun lifecycle(event: LifecycleDiagnostic) {
        val safe = SafeLogEventFactory.lifecycle(event) ?: run { discarded(); return }
        emit(safe.source, safe.level, safe.module, safe.code, safe.session, safe.fields)
    }

    fun diagnostics(diagnostics: CoreDiagnostics) {
        synchronized(lock) {
            val extra = (diagnostics.dropped - coreDropped).coerceAtLeast(0)
            coreDropped = diagnostics.dropped
            mutableState.value = state.value.copy(dropped = state.value.dropped + extra)
        }
        diagnostics.records.forEach { event ->
            val safe = SafeLogEventFactory.diagnostic(event) ?: run { discarded(); return@forEach }
            val age = diagnostics.capturedElapsedMillis?.let { (it - event.elapsedMillis).coerceAtLeast(0) } ?: 0
            emit(safe.source, safe.level, safe.module, safe.code, fields = safe.fields, eventAgeMillis = age)
        }
    }

    /** 仅接收静态 code、模块及数字白名单；任意消息与异常原文没有入队入口。 */
    fun emit(source: LogSource, level: LogLevel, module: LogModule, code: LogCode, session: SessionId? = null, fields: Map<LogField, String> = emptyMap(), eventAgeMillis: Long = 0) {
        synchronized(lock) {
            if (level == LogLevel.Debug && !state.value.debugEnabled) return
            val age = eventAgeMillis.coerceAtLeast(0)
            val record = LogJson.safe(LogRecord(fileId, ++sequence, (wallClock().coerceAtLeast(0) - age).coerceAtLeast(0), (clock.nowMillis().coerceAtLeast(0) - age).coerceAtLeast(0),
                source, level, module, code, session?.value, session?.generation, fields)) ?: run { discarded(); return }
            var bounded = record
            var bytes = LogJson.encode(bounded).size
            if (segmentBytes + bytes > MAX_FILE_BYTES) {
                fileId = UUID.randomUUID().toString(); segmentBytes = 0
                bounded = bounded.copy(fileId = fileId); bytes = LogJson.encode(bounded).size
            }
            segmentBytes += bytes
            val current = state.value
            mutableState.value = current.copy(records = (current.records + bounded).takeLast(2000),
                dropped = current.dropped + if (current.records.size == 2000) 1 else 0)
            if (pending.trySend(Write.Record(bounded.id)).isFailure) discarded()
        }
    }

    /** 导出仅重建通过安全校验的记录；取消关闭存储读取流，不取消采集。输出流归调用方。 */
    suspend fun export(filter: LogFilter, output: OutputStream) = withContext(io) {
        val memory = state.value.records
        val seen = HashSet<String>()
        suspend fun write(record: LogRecord) {
            currentCoroutineContext().ensureActive()
            val safe = LogJson.safe(record) ?: return
            if (seen.add(safe.id) && filter.matches(safe)) output.write(LogJson.encode(safe))
        }
        persistence.read { write(it) }
        memory.forEach { write(it) }
    }

    private fun discarded() = synchronized(lock) { mutableState.value = state.value.copy(dropped = state.value.dropped + 1) }
    private fun diskFailed() = synchronized(lock) { mutableState.value = state.value.copy(persistenceError = true) }

    companion object { const val MAX_FILE_BYTES = 2 * 1024 * 1024 }
}
