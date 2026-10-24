package com.github.zzwtsy.chuanmen.lib.logging

import com.github.zzwtsy.chuanmen.lib.vpn.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SafeLogEventFactoryTest {
    /** 结构化连接、监听、地址与路由事件形成不同安全消息，可按中文搜索，仍保持进程归属。 */
    @Test fun specificEventsAreSearchableAndSafeAcrossStorageAndExport() = runTest {
        val persistence = TestLogPersistence()
        val logs = LogCollector(backgroundScope, persistence, MonotonicClock { 100 }, io = StandardTestDispatcher(testScheduler))
        runCurrent()
        logs.diagnostics(CoreDiagnostics(listOf(
            CoreDiagnostic(90, "core_process", "WARN", "peer", "peer_connect_failed"),
            CoreDiagnostic(90, "core_process", "ERROR", "tunnel", "listener_add_failed"),
            CoreDiagnostic(90, "core_process", "WARN", "config", "ipv4_conflicted"),
            CoreDiagnostic(90, "core_process", "INFO", "route", "proxy_routes_changed", mapOf("count" to "2")),
        ), 0, 128, 100)); runCurrent()
        val records = logs.state.value.records
        assertEquals(4, records.size)
        for (keyword in listOf("连接失败", "监听启动失败", "地址冲突", "路由")) {
            assertEquals(keyword, 1, records.count(LogFilter(keyword = keyword)::matches))
        }
        assertTrue(records.all { it.source == LogSource.EasyTier && it.sessionId == null && it.generation == null })
        assertEquals(mapOf(LogField.Count to "2"), records.last().fields)
        assertTrue(records.all { LogJson.decode(LogJson.encode(it).toString(Charsets.UTF_8).trimEnd()) == it })
        val output = ByteArrayOutputStream()
        logs.export(LogFilter(), output)
        assertEquals(4, output.toString("UTF-8").lineSequence().filter(String::isNotEmpty).count())
        assertEquals(records, persistence.records)
    }

    /** 固定桥事件表的每个 code 都有匹配描述；错误模块、等级、作用域及非白名单数字字段均拒绝。 */
    @Test fun allWireDefinitionsValidateMetadataAndFields() {
        val wires = listOf(
            Triple("peer_added", "INFO", "peer"), Triple("peer_removed", "INFO", "peer"),
            Triple("peer_connection_added", "INFO", "peer"), Triple("peer_connection_removed", "INFO", "peer"),
            Triple("peer_connecting", "DEBUG", "peer"), Triple("peer_connect_failed", "WARN", "peer"),
            Triple("listener_added", "INFO", "tunnel"), Triple("listener_add_failed", "ERROR", "tunnel"),
            Triple("listener_accept_failed", "WARN", "tunnel"), Triple("tunnel_accepted", "INFO", "tunnel"),
            Triple("tunnel_admission_failed", "WARN", "tunnel"), Triple("ipv4_changed", "INFO", "config"),
            Triple("ipv4_conflicted", "WARN", "config"), Triple("proxy_routes_changed", "INFO", "route"),
            Triple("tun_ready", "INFO", "tunnel"), Triple("tun_error", "ERROR", "tunnel"),
            Triple("core_events_unavailable", "WARN", "core"),
        )
        wires.forEach { (code, level, module) ->
            val event = CoreDiagnostic(0, "core_process", level, module, code)
            assertNotNull(code, SafeLogEventFactory.diagnostic(event))
            assertNull(code, SafeLogEventFactory.diagnostic(event.copy(scope = "synthetic-invalid")))
            assertNull(code, SafeLogEventFactory.diagnostic(event.copy(target = "synthetic-secret")))
            assertNull(code, SafeLogEventFactory.diagnostic(event.copy(level = "synthetic-invalid")))
        }
        val event = CoreDiagnostic(0, "core_process", "INFO", "route", "proxy_routes_changed")
        for (fields in listOf(mapOf("url" to "synthetic-secret"), mapOf("count" to "01"), mapOf("count" to "synthetic-secret"))) {
            assertNull(SafeLogEventFactory.diagnostic(event.copy(fields = fields)))
        }
        assertNull(SafeLogEventFactory.diagnostic(event.copy(code = "synthetic-unknown")))
        assertNull(SafeLogEventFactory.diagnostic(event.copy(target = "peer")))
        assertNull(SafeLogEventFactory.diagnostic(event.copy(code = "core_info", fields = mapOf("count" to "1"))))
    }

    /** 既有通用 code 的安全模块仍能读取；即使文件 message 被替换，也只按静态 code 重建消息。 */
    @Test fun legacyRecordsKeepTheirCodesAndIgnoreStoredMessage() {
        val old = LogRecord("00000000-0000-4000-8000-000000000001", 1, 0, 0,
            LogSource.EasyTier, LogLevel.Warn, LogModule.Tunnel, LogCode.CoreWarn)
        val json = com.google.gson.JsonParser.parseString(LogJson.encode(old).toString(Charsets.UTF_8)).asJsonObject
        json.addProperty("message", "synthetic-private-message")
        assertEquals(old, LogJson.decode(json.toString()))
        assertNotNull(SafeLogEventFactory.diagnostic(CoreDiagnostic(0, "core_process", "WARN", "tunnel", "core_warn")))
        val wire = com.google.gson.JsonParser.parseString("""{"elapsed_millis":0,"scope":"core_process","level":"INFO","target":"route","code":"proxy_routes_changed","fields":{"url":"synthetic-secret"}}""").asJsonObject
        val decoded = BridgeProtocolCodec.diagnostic(wire)
        assertFalse(decoded.toString().contains("synthetic-secret"))
        assertNull(SafeLogEventFactory.diagnostic(decoded))
    }
}
