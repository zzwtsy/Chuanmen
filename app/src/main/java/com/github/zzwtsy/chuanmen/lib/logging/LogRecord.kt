package com.github.zzwtsy.chuanmen.lib.logging

import com.google.gson.JsonObject
import com.google.gson.JsonParser

enum class LogSource { App, EasyTier }
enum class LogLevel { Debug, Info, Warn, Error }
enum class LogModule { Session, Core, Peer, Tunnel, Route, Dns, Configuration }
enum class LogField { Operation, Revision, Count }
enum class LogCode(val text: String) {
    Start("开始启动核心"), Apply("开始应用配置"), Stop("开始停止会话"),
    InstallTun("开始安装 VPN 接口"), RemoveTun("开始移除 VPN 接口"), Query("查询运行状态"),
    CoreStarted("核心已启动"), TunAttached("VPN 接口已安装"), TunRemoved("VPN 接口借用已结束"),
    Stopped("会话已停止"), Failed("会话操作失败"), Unknown("会话操作结果未确认"), Progress("连接阶段已更新"), Ready("会话已就绪"),
    CoreInfo("核心运行信息"), CoreWarn("核心运行警告"), CoreError("核心运行错误"), CoreDebug("核心调试事件"),
    PeerAdded("对等节点已加入"), PeerRemoved("对等节点已移除"),
    PeerConnectionAdded("邻居连接已建立"), PeerConnectionRemoved("邻居连接已断开"),
    PeerConnecting("正在建立核心连接"), PeerConnectFailed("核心连接失败"),
    ListenerAdded("隧道监听已启动"), ListenerAddFailed("隧道监听启动失败"), ListenerAcceptFailed("隧道监听接收失败"),
    TunnelAccepted("隧道连接已接收"), TunnelAdmissionFailed("隧道接入失败"),
    Ipv4Changed("虚拟 IPv4 地址已变化"), Ipv4Conflicted("虚拟 IPv4 地址冲突"),
    ProxyRoutesChanged("代理路由已更新"), CoreTunReady("核心 TUN 已就绪"), CoreTunError("核心 TUN 发生错误"),
    CoreEventsUnavailable("核心事件订阅不可用"),
}

/** 身份由日志段 UUID 与进程内递增序号组成；字段只能是白名单内的非负十进制文本。 */
data class LogRecord(
    val fileId: String, val sequence: Long, val wallTimeMillis: Long, val elapsedMillis: Long,
    val source: LogSource, val level: LogLevel, val module: LogModule, val code: LogCode,
    val sessionId: String? = null, val generation: Long? = null,
    val fields: Map<LogField, String> = emptyMap(), val truncated: Boolean = false,
) {
    val id: String get() = "$fileId:$sequence"
    val message: String get() = code.text + fields.entries.sortedBy { it.key.ordinal }.joinToString(separator = "", prefix = "") {
        " · ${when (it.key) { LogField.Operation -> "操作"; LogField.Revision -> "修订"; LogField.Count -> "数量" }} ${it.value}"
    } + if (truncated) "（已截断）" else ""
}

data class LogFilter(val source: LogSource? = null, val minimumLevel: LogLevel = LogLevel.Info, val keyword: String = "", val sessionId: String? = null) {
    fun matches(record: LogRecord) = (source == null || record.source == source) && record.level.ordinal >= minimumLevel.ordinal &&
        (sessionId == null || record.sessionId == sessionId) && record.message.contains(keyword, ignoreCase = true)
}

/** 进入缓冲前和导出时都重建安全字段；文件中的任意 message 文本从不直接展示。 */
object LogJson {
    const val MAX_RECORD_BYTES = 8192
    private val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun safe(record: LogRecord): LogRecord? {
        if (!uuid.matches(record.fileId) || record.sequence <= 0 || record.wallTimeMillis < 0 || record.elapsedMillis < 0) return null
        if (record.sessionId != null && !uuid.matches(record.sessionId)) return null
        if ((record.sessionId == null) != (record.generation == null) || record.generation?.let { it <= 0 } == true) return null
        if (record.source == LogSource.EasyTier && record.sessionId != null) return null
        if (record.fields.values.any { !it.matches(Regex("0|[1-9][0-9]*")) }) return null
        val clipped = record.fields.mapValues { it.value.take(512) }
        return record.copy(fields = clipped, truncated = record.truncated || clipped != record.fields)
    }
    fun encode(record: LogRecord): ByteArray {
        val safe = requireNotNull(safe(record))
        val json = JsonObject().apply {
            addProperty("file_id", safe.fileId); addProperty("sequence", safe.sequence)
            addProperty("wall_time_ms", safe.wallTimeMillis); addProperty("elapsed_ms", safe.elapsedMillis)
            addProperty("source", safe.source.name); addProperty("level", safe.level.name); addProperty("module", safe.module.name)
            addProperty("code", safe.code.name); addProperty("session_id", safe.sessionId); addProperty("generation", safe.generation)
            addProperty("message", safe.message); addProperty("truncated", safe.truncated)
            add("fields", JsonObject().apply { safe.fields.forEach { (key, value) -> addProperty(key.name, value) } })
        }
        return (json.toString() + "\n").toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_RECORD_BYTES) }
    }
    fun decode(line: String): LogRecord? = runCatching {
        require(line.toByteArray(Charsets.UTF_8).size + 1 <= MAX_RECORD_BYTES)
        val json = JsonParser.parseString(line).asJsonObject
        fun text(name: String) = json[name].takeUnless { it.isJsonNull }?.asString
        fun number(name: String) = requireNotNull(text(name)).also { require(it.matches(Regex("0|[1-9][0-9]*"))) }.toLong()
        safe(LogRecord(requireNotNull(text("file_id")), number("sequence"), number("wall_time_ms"), number("elapsed_ms"),
            LogSource.valueOf(requireNotNull(text("source"))), LogLevel.valueOf(requireNotNull(text("level"))),
            LogModule.valueOf(requireNotNull(text("module"))), LogCode.valueOf(requireNotNull(text("code"))),
            text("session_id"), text("generation")?.toLong(),
            json.getAsJsonObject("fields").entrySet().associate { LogField.valueOf(it.key) to it.value.asString },
            json["truncated"].asBoolean))
    }.getOrNull()
}

/** 存储实现位于日志功能；采集器只依赖有实际消费者的流式持久化契约。 */
interface LogPersistence {
    fun prepare()
    fun append(record: LogRecord)
    suspend fun read(consume: suspend (LogRecord) -> Unit)
}
