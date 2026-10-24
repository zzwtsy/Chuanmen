package com.github.zzwtsy.chuanmen.lib.logging

/** 仅供 JVM 状态测试记录真实采集器写入，不参与正式装配。 */
class TestLogPersistence : LogPersistence {
    val records = mutableListOf<LogRecord>()
    var fail = false
    override fun prepare() { if (fail) error("synthetic-storage-failure") }
    override fun append(record: LogRecord) { if (fail) error("synthetic-storage-failure"); records += record }
    override suspend fun read(consume: suspend (LogRecord) -> Unit) { records.toList().forEach { consume(it) } }
}
