package com.github.zzwtsy.chuanmen.lib.vpn

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.google.gson.internal.Streams
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigInteger

/** 严格校验协议和归属；整数从十进制文本读取，绝不经过 Double。 */
internal object BridgeProtocolCodec {
    const val CORE_REVISION = "7592951b808c4839cb9d77cc4bc642f124915951"
    fun request(action: String, token: OperationToken? = null, attachment: AttachmentId? = null): JsonObject = JsonObject().apply {
        addProperty("protocol", BridgeProtocol.VERSION)
        addProperty("action", action)
        token?.let {
            addProperty("session_id", it.sessionId.value)
            addProperty("generation", it.sessionId.generation)
            addProperty("operation", it.sequence)
        }
        attachment?.let { addProperty("attachment", it.sequence) }
    }

    fun decode(json: String, token: OperationToken? = null, attachment: AttachmentId? = null): JsonObject {
        require(json.length <= 1_048_576)
        val reader = JsonReader(StringReader(json)).apply { strictness = Strictness.STRICT }
        val root = reader.use {
            val value = Streams.parse(it)
            require(it.peek() == JsonToken.END_DOCUMENT)
            value.asJsonObject
        }
        require(root.long("protocol") == BridgeProtocol.VERSION.toLong())
        if (token != null) {
            require(root.string("session_id") == token.sessionId.value)
            require(root.long("generation") == token.sessionId.generation)
            require(root.long("operation") == token.sequence)
        }
        if (attachment != null) require(root.long("attachment") == attachment.sequence)
        require(root.string("status") in setOf("confirmed", "failed", "unknown", "rejected"))
        return root
    }

    /** 诊断字段只解码已知 count；异常形状交由事件工厂拒绝，不读取未知字段原文。 */
    fun diagnostic(record: JsonObject): CoreDiagnostic {
        val raw = record.get("fields")?.takeUnless { it.isJsonNull }
        val fields = when {
            raw == null -> emptyMap()
            !raw.isJsonObject || raw.asJsonObject.keySet().any { it != "count" } -> mapOf("invalid" to "")
            !raw.asJsonObject.has("count") -> emptyMap()
            else -> raw.asJsonObject.get("count").let {
                if (it.isJsonPrimitive && it.asJsonPrimitive.isString) mapOf("count" to it.asString) else mapOf("invalid" to "")
            }
        }
        return CoreDiagnostic(record.long("elapsed_millis"), record.string("scope"), record.string("level"), record.string("target"), record.string("code"), fields)
    }

    fun snapshot(root: JsonObject, token: OperationToken): CoreSnapshot {
        val result = root.getAsJsonObject("result")
        val capabilities = result.getAsJsonArray("capabilities")?.map { it.asString }?.toSet().orEmpty()
        val extended = "observability-v1" in capabilities
        val classified = "observability-v2" in capabilities
        val attachment = result.objectOrNull("attachment")?.let {
            require(it.string("session_id") == token.sessionId.value && it.long("generation") == token.sessionId.generation)
            AttachmentId(token.sessionId, it.long("attachment"))
        }
        val rawCounters = result.objectOrNull("tun_counters")
        val counterMetrics = rawCounters?.let { raw ->
            listOf("tx_bytes", "rx_bytes", "tx_packets", "rx_packets").map { name ->
                if (extended) raw.unsignedMetric(name) else CoreMetric(BigInteger.valueOf(raw.long(name)))
            }
        }
        val counterFailure = counterMetrics?.firstOrNull { it.value == null }?.unavailable
            ?: counterMetrics?.firstOrNull { it.value!! > BigInteger.valueOf(Long.MAX_VALUE) }?.let { MetricUnavailable.OutOfRange }
        val counters = counterMetrics?.takeIf { counterFailure == null }?.map { it.value!!.toLong() }?.let {
            TunCounters(it[0], it[1], it[2], it[3])
        }
        return CoreSnapshot(
            token.sessionId, OperationToken(token.sessionId, result.long("last_operation")), result.long("saved_revision"),
            when (result.string("phase")) {
                "starting" -> RuntimeStatus.Starting
                "core_running", "running" -> if (result.stringOrNull("core_state") == "running") RuntimeStatus.Running else RuntimeStatus.Unknown
                "stopping" -> RuntimeStatus.Stopping
                "stopped", "released" -> RuntimeStatus.Stopped
                else -> RuntimeStatus.Unknown
            },
            result.stringOrNull("ipv4")?.let(::cidr),
            result.getAsJsonArray("peers").map { it.asJsonObject.let { peer ->
                CorePeer(peer.long("peer_id"), peer.long("connections"),
                    if (extended) peer.getAsJsonArray("links")?.map { element ->
                        val link = element.asJsonObject
                        CoreLink(link.string("connection_id"), link.unsignedMetric("tx_bytes"), link.unsignedMetric("rx_bytes"),
                            link.unsignedMetric("tx_packets"), link.unsignedMetric("rx_packets"),
                            if (!classified) CoreMetric.unsupported() else when (link.get("latency_valid")?.takeUnless { it.isJsonNull }?.let {
                                if (it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) it.asBoolean else null
                            }) {
                                true -> link.unsignedMetric("latency_us")
                                false -> CoreMetric.missing()
                                null -> CoreMetric(null, if (link.has("latency_valid") && !link.get("latency_valid").isJsonNull)
                                    MetricUnavailable.Invalid else MetricUnavailable.Missing)
                            })
                    }.orEmpty() else emptyList(),
                    if (classified) when (peer.get("network_role")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString) {
                        "member" -> PeerNetworkRole.Member
                        "relay" -> PeerNetworkRole.Relay
                        else -> PeerNetworkRole.Unknown
                    } else PeerNetworkRole.Unknown)
            } },
            result.getAsJsonArray("routes").map { it.asJsonObject.let { route ->
                CoreRoute(route.long("peer_id"), route.stringOrNull("ipv4")?.let(::cidr),
                    route.getAsJsonArray("proxy_cidrs").map { cidr(it.asString) }, route.signedLong("cost"), route.string("version"),
                    route.metric("hostname", extended) { string("hostname").takeIf(String::isNotBlank) },
                    route.metric("next_hop_peer_id", extended) { long("next_hop_peer_id") },
                    route.metric("udp_nat_type", extended) { signedLong("udp_nat_type").intExact() },
                    route.metric("tcp_nat_type", extended) { signedLong("tcp_nat_type").intExact() })
            } },
            result.optionalInt("udp_nat_type"), result.optionalInt("tcp_nat_type"), attachment,
            result.stringOrNull("attachment_phase")?.let { when (it) {
                "pending" -> TunBorrowState.Pending
                "installed" -> TunBorrowState.Installed
                "released" -> TunBorrowState.Released
                "failed" -> TunBorrowState.Failed
                else -> TunBorrowState.Unknown
            } }, counters, result.long("active_instances"), result.long("callbacks_in_flight"), result.long("jni_global_refs"),
            result.long("protection_calls"), result.long("protection_failures"),
            if (result.has("runtime_threads")) result.long("runtime_threads") else null,
            capabilities = capabilities,
            localPeerId = if (extended) result.metric("local_peer_id", true) { long("local_peer_id") }.value else null,
            localHostname = result.metric("local_hostname", extended) { string("local_hostname").takeIf(String::isNotBlank) },
            localVersion = result.metric("local_version", extended) { string("local_version").takeIf(String::isNotBlank) },
            countersUnavailable = if (counters == null) counterFailure ?: MetricUnavailable.Missing else null,
        )
    }

    /** 只接受规范十进制字符串，保留完整 u64；非法单字段不破坏其他指标。 */
    private fun JsonObject.unsignedMetric(name: String): CoreMetric<BigInteger> = metric(name, true) {
        val text = string(name)
        require(text.matches(Regex("0|[1-9][0-9]{0,19}")))
        val value = BigInteger(text)
        if (value > BigInteger("18446744073709551615")) throw ArithmeticException()
        value
    }

    private fun <T> JsonObject.metric(name: String, supported: Boolean, read: JsonObject.() -> T?): CoreMetric<T> {
        if (!supported) return CoreMetric.unsupported()
        if (!has(name) || get(name).isJsonNull) return CoreMetric.missing()
        return try { read()?.let { CoreMetric(it) } ?: CoreMetric.missing() }
        catch (_: ArithmeticException) { CoreMetric(null, MetricUnavailable.OutOfRange) }
        catch (_: Exception) { CoreMetric(null, MetricUnavailable.Invalid) }
    }

    private fun Long.intExact(): Int {
        if (this !in Int.MIN_VALUE..Int.MAX_VALUE) throw ArithmeticException()
        return toInt()
    }

    private fun JsonObject.signedLong(name: String): Long {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        require(value.asString.matches(Regex("0|-?[1-9][0-9]*")))
        return value.asString.toLong()
    }

    fun cidr(value: String): Ipv4Cidr {
        val parts = value.split('/')
        require(parts.size in 1..2)
        return Ipv4Cidr(parts[0], if (parts.size == 2) parts[1].toInt() else 32)
    }
    fun JsonObject.long(name: String): Long {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber)
        val text = value.asString
        require(text.matches(Regex("0|[1-9][0-9]*")))
        return text.toLong()
    }
    fun JsonObject.string(name: String): String {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString)
        return value.asString
    }
    fun JsonObject.bool(name: String): Boolean {
        val value = get(name)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isBoolean)
        return value.asBoolean
    }
    fun JsonObject.stringOrNull(name: String): String? = get(name)?.takeUnless(JsonElement::isJsonNull)?.let { string(name) }
    fun JsonObject.objectOrNull(name: String): JsonObject? = get(name)?.takeUnless(JsonElement::isJsonNull)?.asJsonObject
    private fun JsonObject.optionalInt(name: String): Int? = get(name)?.takeUnless(JsonElement::isJsonNull)?.let {
        runCatching { signedLong(name).intExact() }.getOrNull()
    }
}
