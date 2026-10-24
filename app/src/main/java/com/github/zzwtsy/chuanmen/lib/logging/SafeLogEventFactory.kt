package com.github.zzwtsy.chuanmen.lib.logging

import com.github.zzwtsy.chuanmen.lib.vpn.CoreDiagnostic
import com.github.zzwtsy.chuanmen.lib.vpn.LifecycleDiagnostic
import com.github.zzwtsy.chuanmen.lib.vpn.SessionId

/** 工厂产物只含静态消息定义及安全数字；记录身份、时钟和存储预算仍由采集器管理。 */
internal data class SafeLogEvent(
    val source: LogSource, val level: LogLevel, val module: LogModule, val code: LogCode,
    val session: SessionId? = null, val fields: Map<LogField, String> = emptyMap(),
)

internal object SafeLogEventFactory {
    private data class Definition(val code: LogCode, val source: LogSource, val level: LogLevel,
        val module: LogModule?, val fields: Set<LogField> = emptySet())

    private val lifecycle = listOf(
        "command_start" to LogCode.Start, "command_apply" to LogCode.Apply, "command_stop" to LogCode.Stop,
        "command_install_tun" to LogCode.InstallTun, "command_remove_tun" to LogCode.RemoveTun, "command_query" to LogCode.Query,
        "receipt_core_started" to LogCode.CoreStarted, "receipt_tun_attached" to LogCode.TunAttached,
        "receipt_tun_removed" to LogCode.TunRemoved, "receipt_stopped" to LogCode.Stopped,
        "receipt_failed" to LogCode.Failed, "receipt_unknown" to LogCode.Unknown,
        "receipt_progress" to LogCode.Progress, "receipt_ready" to LogCode.Ready,
    ).associate { (wire, code) -> wire to Definition(code, LogSource.App, when (code) {
        LogCode.Query -> LogLevel.Debug; LogCode.Failed -> LogLevel.Error; LogCode.Unknown -> LogLevel.Warn; else -> LogLevel.Info
    }, LogModule.Session, setOf(LogField.Operation, LogField.Revision)) }

    private fun core(code: LogCode, level: LogLevel, module: LogModule?, count: Boolean = false) =
        Definition(code, LogSource.EasyTier, level, module, if (count) setOf(LogField.Count) else emptySet())

    private val diagnostics = mapOf(
        "peer_added" to core(LogCode.PeerAdded, LogLevel.Info, LogModule.Peer),
        "peer_removed" to core(LogCode.PeerRemoved, LogLevel.Info, LogModule.Peer),
        "peer_connection_added" to core(LogCode.PeerConnectionAdded, LogLevel.Info, LogModule.Peer),
        "peer_connection_removed" to core(LogCode.PeerConnectionRemoved, LogLevel.Info, LogModule.Peer),
        "peer_connecting" to core(LogCode.PeerConnecting, LogLevel.Debug, LogModule.Peer),
        "peer_connect_failed" to core(LogCode.PeerConnectFailed, LogLevel.Warn, LogModule.Peer),
        "listener_added" to core(LogCode.ListenerAdded, LogLevel.Info, LogModule.Tunnel),
        "listener_add_failed" to core(LogCode.ListenerAddFailed, LogLevel.Error, LogModule.Tunnel),
        "listener_accept_failed" to core(LogCode.ListenerAcceptFailed, LogLevel.Warn, LogModule.Tunnel),
        "tunnel_accepted" to core(LogCode.TunnelAccepted, LogLevel.Info, LogModule.Tunnel),
        "tunnel_admission_failed" to core(LogCode.TunnelAdmissionFailed, LogLevel.Warn, LogModule.Tunnel),
        "ipv4_changed" to core(LogCode.Ipv4Changed, LogLevel.Info, LogModule.Configuration),
        "ipv4_conflicted" to core(LogCode.Ipv4Conflicted, LogLevel.Warn, LogModule.Configuration),
        "proxy_routes_changed" to core(LogCode.ProxyRoutesChanged, LogLevel.Info, LogModule.Route, count = true),
        "tun_ready" to core(LogCode.CoreTunReady, LogLevel.Info, LogModule.Tunnel),
        "tun_error" to core(LogCode.CoreTunError, LogLevel.Error, LogModule.Tunnel),
        "core_events_unavailable" to core(LogCode.CoreEventsUnavailable, LogLevel.Warn, LogModule.Core),
        // 保留旧通用 code 与全部安全模块，既有日志文件无需改写。
        "core_info" to core(LogCode.CoreInfo, LogLevel.Info, null),
        "core_warn" to core(LogCode.CoreWarn, LogLevel.Warn, null),
        "core_error" to core(LogCode.CoreError, LogLevel.Error, null),
        "core_debug" to core(LogCode.CoreDebug, LogLevel.Debug, null),
    )
    private val modules = mapOf("core" to LogModule.Core, "peer" to LogModule.Peer, "tunnel" to LogModule.Tunnel,
        "route" to LogModule.Route, "dns" to LogModule.Dns, "config" to LogModule.Configuration)
    private val levels = mapOf("DEBUG" to LogLevel.Debug, "INFO" to LogLevel.Info, "WARN" to LogLevel.Warn, "ERROR" to LogLevel.Error)

    fun lifecycle(event: LifecycleDiagnostic): SafeLogEvent? {
        val definition = lifecycle[event.code] ?: return null
        return SafeLogEvent(definition.source, definition.level, requireNotNull(definition.module), definition.code, event.token.sessionId,
            mapOf(LogField.Operation to event.token.sequence.toString(), LogField.Revision to event.savedRevision.toString()))
    }

    fun diagnostic(event: CoreDiagnostic): SafeLogEvent? {
        if (event.scope != "core_process") return null
        val definition = diagnostics[event.code] ?: return null
        val module = modules[event.target] ?: return null
        if (levels[event.level] != definition.level || definition.module?.let { it != module } == true) return null
        if (event.fields.keys.any { it != "count" } || (event.fields.isNotEmpty() && LogField.Count !in definition.fields)) return null
        val fields = event.fields["count"]?.let {
            if (!it.matches(Regex("0|[1-9][0-9]*"))) return null
            mapOf(LogField.Count to it)
        }.orEmpty()
        return SafeLogEvent(definition.source, definition.level, module, definition.code, fields = fields)
    }
}
