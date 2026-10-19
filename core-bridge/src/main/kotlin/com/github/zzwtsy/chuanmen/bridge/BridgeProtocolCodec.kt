package com.github.zzwtsy.chuanmen.bridge

import com.github.zzwtsy.chuanmen.contracts.*
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.Strictness
import com.google.gson.internal.Streams
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader

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

    fun snapshot(root: JsonObject, token: OperationToken): CoreSnapshot {
        val result = root.getAsJsonObject("result")
        val attachment = result.objectOrNull("attachment")?.let {
            require(it.string("session_id") == token.sessionId.value && it.long("generation") == token.sessionId.generation)
            AttachmentId(token.sessionId, it.long("attachment"))
        }
        val counters = result.objectOrNull("tun_counters")?.let {
            TunCounters(it.long("tx_bytes"), it.long("rx_bytes"), it.long("tx_packets"), it.long("rx_packets"))
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
            result.getAsJsonArray("peers").map { it.asJsonObject.let { peer -> CorePeer(peer.long("peer_id"), peer.long("connections")) } },
            result.getAsJsonArray("routes").map { it.asJsonObject.let { route ->
                CoreRoute(route.long("peer_id"), route.stringOrNull("ipv4")?.let(::cidr),
                    route.getAsJsonArray("proxy_cidrs").map { cidr(it.asString) }, route.long("cost"), route.string("version"))
            } },
            result.optionalInt("udp_nat_type"), result.optionalInt("tcp_nat_type"), attachment,
            result.stringOrNull("attachment_phase")?.let { when (it) {
                "pending" -> TunBorrowState.Pending
                "installed" -> TunBorrowState.Installed
                "released" -> TunBorrowState.Released
                "failed" -> TunBorrowState.Failed
                else -> error("非法借用状态")
            } }, counters, result.long("active_instances"), result.long("callbacks_in_flight"), result.long("jni_global_refs"),
            result.long("protection_calls"), result.long("protection_failures"),
            if (result.has("runtime_threads")) result.long("runtime_threads") else null,
        )
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
        val value = long(name)
        require(value <= Int.MAX_VALUE)
        value.toInt()
    }
}
