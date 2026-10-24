package com.github.zzwtsy.chuanmen.features.logs

import com.github.zzwtsy.chuanmen.lib.logging.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 应用私有非备份目录中的 JSONL；文件名保存创建时刻，写入不延长保留期。 */
class RotatingLogStore(private val directory: File, private val now: () -> Long = System::currentTimeMillis) : LogPersistence {
    private val lock = Any()
    private var activeId: String? = null
    private var activeFile: File? = null
    override fun prepare() = synchronized(lock) {
        check(directory.isDirectory || directory.mkdirs())
        clean()
        val checkFile = File(directory, ".write-${UUID.randomUUID()}")
        try { checkFile.outputStream().use { it.write(0) } } finally { checkFile.delete() }
        Unit
    }
    override fun append(record: LogRecord) = synchronized(lock) {
        val bytes = LogJson.encode(record)
        if (activeId != record.fileId || activeFile?.exists() != true) {
            activeId = record.fileId
            val order = (files().maxOfOrNull { it.name.substringBefore('-').toLong() } ?: 0) + 1
            activeFile = File(directory, "$order-${now().coerceAtLeast(0)}-${record.fileId}.jsonl")
            clean(reserve = 1)
        }
        val file = requireNotNull(activeFile)
        check(file.length() + bytes.size <= LogCollector.MAX_FILE_BYTES)
        file.appendBytes(bytes)
        Unit
    }
    override suspend fun read(consume: suspend (LogRecord) -> Unit) {
        // 不在读取期间持有写锁；轮转删除已打开的文件不阻塞采集。
        val files = synchronized(lock) { files() }
        for (file in files) {
            currentCoroutineContext().ensureActive()
            val input = try { file.bufferedReader(Charsets.UTF_8) } catch (_: java.io.FileNotFoundException) { continue }
            input.use { reader ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = reader.readLine() ?: break
                    LogJson.decode(line)?.let { record ->
                        if (file.name.endsWith("-${record.fileId}.jsonl")) consume(record)
                    }
                }
            }
        }
    }
    private fun files() = directory.listFiles().orEmpty().filter { it.isFile && it.name.matches(Regex("[0-9]+-[0-9]+-[0-9a-f-]{36}\\.jsonl")) }
        .sortedBy { it.name.substringBefore('-').toLong() }
    private fun created(file: File) = file.name.substringAfter('-').substringBefore('-').toLong()
    private fun clean(reserve: Int = 0) {
        val timestamp = now().coerceAtLeast(0)
        files().forEach { file ->
            val age = timestamp - created(file)
            if (age > RETENTION_MILLIS) check(file.delete())
        }
        val remaining = files()
        remaining.take((remaining.size + reserve - 5).coerceAtLeast(0)).forEach { check(it.delete()) }
    }
    companion object { const val RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000 }
}
