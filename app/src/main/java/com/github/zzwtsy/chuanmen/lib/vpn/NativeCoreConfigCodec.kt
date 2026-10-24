package com.github.zzwtsy.chuanmen.lib.vpn

import com.github.zzwtsy.chuanmen.lib.profile.*
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 唯一 .so 的无会话配置入口；JNI 编码、错误与数值转换均留在此边界。 */
class NativeCoreConfigCodec internal constructor(
    private val port: EnginePort,
    private val dispatcher: CoroutineDispatcher,
) : CoreConfigCodec {
    constructor() : this(JniEnginePort, Dispatchers.IO)

    override suspend fun parse(raw: String, identity: CoreInstanceId): ConfigResult<CoreConfigDocument> =
        document(call("config_parse", raw), identity)

    override suspend fun create(core: CoreConfig): ConfigResult<CoreConfigDocument> {
        val empty = CoreConfigDocument("", core, "", emptyList())
        return patch(empty, listOf(CoreConfigChange.NetworkName(core.networkName), CoreConfigChange.Secret(core.networkSecret),
            CoreConfigChange.Hostname(core.hostname), CoreConfigChange.Address(core.ipv4), CoreConfigChange.PeerEdits(core.peers.map(PeerPatchEntry::Insert)), CoreConfigChange.Mtu(core.mtu)))
    }

    override suspend fun patch(document: CoreConfigDocument, changes: List<CoreConfigChange>): ConfigResult<CoreConfigDocument> {
        if (changes.isEmpty()) return ConfigResult.Success(document)
        val patch = JsonObject()
        changes.forEach { change -> when (change) {
            is CoreConfigChange.NetworkName -> patch.addProperty("network_identity.network_name", change.value)
            is CoreConfigChange.Secret -> patch.addProperty("network_identity.network_secret", change.value?.revealForCore())
            is CoreConfigChange.Hostname -> patch.addProperty("hostname", change.value)
            is CoreConfigChange.Mtu -> patch.addProperty("flags.mtu", change.value)
            is CoreConfigChange.Address -> {
                patch.addProperty("dhcp", change.value == Ipv4Assignment.Automatic)
                // 自动模式不擅自删除原文中的静态地址；关闭自动后仍能恢复该地址。
                (change.value as? Ipv4Assignment.Static)?.let { patch.addProperty("ipv4", it.address) }
                if (change.value == Ipv4Assignment.Unspecified) patch.add("ipv4", JsonNull.INSTANCE)
            }
            is CoreConfigChange.PeerEdits -> patch.add("peer_edits", JsonArray().apply {
                change.entries.forEach { entry -> add(JsonObject().apply { when (entry) {
                    is PeerPatchEntry.Keep -> { addProperty("kind", "keep"); addProperty("source_index", entry.sourceIndex) }
                    is PeerPatchEntry.ReplaceUri -> { addProperty("kind", "replace_uri"); addProperty("source_index", entry.sourceIndex); addProperty("uri", entry.uri.value) }
                    is PeerPatchEntry.Insert -> { addProperty("kind", "insert"); addProperty("uri", entry.uri.value) }
                } }) }
            })
        } }
        return document(call("config_patch", document.raw, patch), document.core.instanceId)
    }

    override suspend fun encodeForRuntime(document: CoreConfigDocument): ConfigResult<RuntimeCoreConfig> {
        val identity = JsonObject().apply {
            addProperty("instance_name", document.core.instanceId.value)
            addProperty("instance_id", UUID.nameUUIDFromBytes(document.core.instanceId.value.toByteArray(Charsets.UTF_8)).toString())
        }
        return when (val response = call("config_runtime", document.raw, identity)) {
            is ConfigResult.Failure -> response
            is ConfigResult.Success -> try {
                if (!response.value["runnable"].asBoolean) ConfigResult.Failure(ConfigProblem(ConfigProblemCode.UnsupportedField,
                    unsupportedFields = Collections.unmodifiableList(response.value.getAsJsonArray("unsupported").map { it.asString })))
                else ConfigResult.Success(RuntimeCoreConfig(response.value["raw"].asString,
                    Collections.unmodifiableList(response.value.getAsJsonArray("overrides").map { it.asString })))
            } catch (_: Exception) { ConfigResult.Failure(ConfigProblem(ConfigProblemCode.Protocol)) }
        }
    }

    private fun document(result: ConfigResult<JsonObject>, identity: CoreInstanceId): ConfigResult<CoreConfigDocument> = when (result) {
        is ConfigResult.Failure -> result
        is ConfigResult.Success -> try {
            val response = result.value
            val fields = response.getAsJsonObject("projection")
            val network = fields.getAsJsonObject("network_identity")
            val automatic = fields["dhcp"]?.asBoolean ?: false
            val address = fields["ipv4"]?.asString
            val core = CoreConfig(identity, network?.get("network_name")?.asString ?: "default",
                fields["hostname"]?.asString,
                if (automatic) Ipv4Assignment.Automatic else if (address != null) Ipv4Assignment.Static(address) else Ipv4Assignment.Unspecified,
                fields.getAsJsonObject("flags")?.getAsJsonObject("mtu")?.get("decimal")?.asString?.toInt() ?: 1380,
                network?.get("network_secret")?.asString?.let(NetworkSecret::of),
                Collections.unmodifiableList(fields.getAsJsonArray("peer")?.mapNotNull { runCatching { PeerEndpoint(it.asJsonObject["uri"].asString) }.getOrNull() }.orEmpty()),
            )
            ConfigResult.Success(CoreConfigDocument(response["raw"].asString, core, response["semantic"].toString(),
                Collections.unmodifiableList(response.getAsJsonArray("unsupported").map { it.asString }),
                response["runtime_semantic"]?.toString() ?: response["semantic"].toString(), address,
                fields.getAsJsonArray("peer")?.map { it.asJsonObject["uri"].asString }.orEmpty()))
        } catch (_: Exception) { ConfigResult.Failure(ConfigProblem(ConfigProblemCode.InvalidConfig)) }
    }

    private suspend fun call(action: String, raw: String, patch: JsonObject? = null): ConfigResult<JsonObject> = withContext(dispatcher) {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_DOCUMENT_BYTES) return@withContext ConfigResult.Failure(ConfigProblem(ConfigProblemCode.DocumentTooLarge))
        val request = JsonObject().apply {
            addProperty("protocol", BridgeProtocol.VERSION)
            addProperty("action", action)
            addProperty("config", raw)
            patch?.let { add("patch", it) }
        }.toString()
        if (request.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BYTES) return@withContext ConfigResult.Failure(ConfigProblem(ConfigProblemCode.RequestTooLarge))
        try {
            val response = JsonParser.parseString(port.request(request, null)).asJsonObject
            require(response["protocol"].asJsonPrimitive.isNumber && response["protocol"].asBigDecimal.longValueExact() == BridgeProtocol.VERSION.toLong())
            if (response["status"].asString == "confirmed") {
                val result = response.getAsJsonObject("result")
                require(result["codec_version"].asJsonPrimitive.isNumber && result["codec_version"].asBigDecimal.intValueExact() == 2)
                ConfigResult.Success(result)
            }
            else {
                val code = when (response["code"]?.asString) {
                    "toml_syntax" -> ConfigProblemCode.Syntax
                    "document_too_large" -> ConfigProblemCode.DocumentTooLarge
                    "unsupported_field" -> ConfigProblemCode.UnsupportedField
                    "invalid_config", "field_type", "invalid_patch" -> ConfigProblemCode.InvalidConfig
                    else -> ConfigProblemCode.Protocol
                }
                val offset = response.getAsJsonObject("result")?.get("offset")?.takeUnless { it is JsonNull }?.asInt
                ConfigResult.Failure(ConfigProblem(code, fieldKey = response.getAsJsonObject("result")?.get("key")?.takeUnless { it is JsonNull }?.asString, byteOffset = offset))
            }
        } catch (_: LinkageError) { ConfigResult.Failure(ConfigProblem(ConfigProblemCode.NativeUnavailable)) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { ConfigResult.Failure(ConfigProblem(ConfigProblemCode.Protocol)) }
    }

    companion object {
        const val MAX_DOCUMENT_BYTES = CoreConfigDocument.MAX_BYTES
        const val MAX_REQUEST_BYTES = 256 * 1024
    }
}
