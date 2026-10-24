package com.github.zzwtsy.chuanmen.lib.profile

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Collections

/** 存储错误只带稳定分类，不携带原始 JSON、密码或底层异常文本。 */
class ProfileStorageException(val reason: Reason) : Exception("配置存储失败：${reason.name}") {
    enum class Reason { Read, Write, InvalidDocument, NewerSchema, KeyUnavailable, StoreTooLarge, DocumentTooLarge }
}

/** 文档原树用于保留未知字段；禁止默认字符串输出泄漏解密后的内容。 */
internal class ProfileDocument(tree: JsonObject, profiles: List<ProfileSummary>, val connectionTarget: LocalProfileId? = null) {
    private val source = tree.deepCopy()
    val profiles: List<ProfileSummary> = Collections.unmodifiableList(profiles.map { it.copy(saved = it.saved.frozen()) })
    fun copyTree(): JsonObject = source.deepCopy()
    override fun toString() = "ProfileDocument(<redacted>)"
}

/** 字节和候选快照来自同一棵树；写入前不能发布，外部也不能修改待写字节。 */
internal class PreparedProfileCommit(val snapshot: ProfileDocument, bytes: ByteArray) {
    private val encoded = bytes.copyOf()
    fun bytes(): ByteArray = encoded.copyOf()
    override fun toString() = "PreparedProfileCommit(<redacted>)"
}

/** 显式存储 schema，不依赖 Gson 反射构造领域对象；所有数字以整数文本处理。 */
internal object ProfileDocumentCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_BYTES = 8 * 1024 * 1024
    const val CORE_SOURCE = "7592951b808c4839cb9d77cc4bc642f124915951"

    fun empty() = ProfileDocument(JsonObject().apply {
        addProperty("schemaVersion", SCHEMA_VERSION)
        addProperty("sourceVersion", CORE_SOURCE)
        add("profiles", JsonArray())
    }, emptyList())

    suspend fun decode(bytes: ByteArray, codec: CoreConfigCodec): ProfileDocument = try {
        if (bytes.size > MAX_BYTES) throw ProfileStorageException(ProfileStorageException.Reason.StoreTooLarge)
        val root = JsonParser.parseString(Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()).asJsonObject
        val schema = root.long("schemaVersion").let { Math.toIntExact(it) }
        if (schema > SCHEMA_VERSION) throw ProfileStorageException(ProfileStorageException.Reason.NewerSchema)
        require(schema == SCHEMA_VERSION)
        require(root.get("sourceVersion").asString.isNotBlank())
        val records = root.getAsJsonArray("profiles")
        val profiles = records.map { item ->
            val record = item.asJsonObject
            val policy = record.getAsJsonObject("policy")
            val local = record.getAsJsonObject("metadata")
            val metadata = LocalProfileMetadata(local.stringOrNull("localDisplayName"), local.long("sortOrder"),
                local.long("createdAtMillis"), local.long("updatedAtMillis"))
            require(metadata.createdAtMillis >= 0 && metadata.updatedAtMillis >= metadata.createdAtMillis)
            require(record.get("sourceVersion").asString.isNotBlank())
            val raw = record.get("coreToml").asString
            if (raw.toByteArray(Charsets.UTF_8).size > CoreConfigDocument.MAX_BYTES) throw ProfileStorageException(ProfileStorageException.Reason.DocumentTooLarge)
            val parsed = codec.parse(raw, CoreInstanceId(record.get("coreInstanceId").asString))
            val document = when (parsed) {
                is ConfigResult.Success -> parsed.value
                is ConfigResult.Failure -> throw ProfileStorageException(if (parsed.problem.code == ConfigProblemCode.DocumentTooLarge)
                    ProfileStorageException.Reason.DocumentTooLarge else ProfileStorageException.Reason.InvalidDocument)
            }
            val saved = SavedRevision(
                LocalProfileId(record.get("profileId").asString), record.long("configRevision"), document,
                AndroidVpnPolicy(policy.boolean("includeInternet"),
                    policy.getAsJsonArray("dnsServers").map { it.asString }, Ipv6Policy.valueOf(policy.get("ipv6").asString)),
            ).frozen()
            ProfileSummary(saved, metadata.localDisplayName?.trim()?.takeIf(String::isNotEmpty) ?: saved.core.networkName, metadata)
        }
        require(profiles.map { it.saved.profileId }.distinct().size == profiles.size)
        val target = root.stringOrNull("lastConnectionTarget")?.let(::LocalProfileId)
        require(target == null || profiles.any { it.saved.profileId == target })
        ProfileDocument(root, Collections.unmodifiableList(profiles.sortedBy { it.metadata.sortOrder }), target)
    } catch (error: ProfileStorageException) {
        throw error
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        throw ProfileStorageException(ProfileStorageException.Reason.InvalidDocument)
    }

    /** 覆盖已知字段时保留根、记录、核心、策略及元数据中的未知字段。 */
    fun encode(previous: ProfileDocument, profiles: List<ProfileSummary>, target: LocalProfileId? = previous.connectionTarget): ByteArray {
        return prepare(previous, profiles, target).bytes()
    }

    fun prepare(previous: ProfileDocument, profiles: List<ProfileSummary>, target: LocalProfileId? = previous.connectionTarget): PreparedProfileCommit {
        require(profiles.map { it.saved.profileId }.distinct().size == profiles.size)
        require(target == null || profiles.any { it.saved.profileId == target })
        val root = previous.copyTree()
        root.addProperty("schemaVersion", SCHEMA_VERSION)
        root.addProperty("lastConnectionTarget", target?.value)
        val old = root.getAsJsonArray("profiles").associate { it.asJsonObject.get("profileId").asString to it.asJsonObject }
        root.add("profiles", JsonArray().apply {
            profiles.forEach { profile ->
                val saved = profile.saved
                require(saved.revision > 0)
                require(profile.metadata.createdAtMillis >= 0 && profile.metadata.updatedAtMillis >= profile.metadata.createdAtMillis)
                if (saved.document.raw.toByteArray(Charsets.UTF_8).size > CoreConfigDocument.MAX_BYTES)
                    throw ProfileStorageException(ProfileStorageException.Reason.DocumentTooLarge)
                val record = old[saved.profileId.value]?.deepCopy() ?: JsonObject()
                record.addProperty("profileId", saved.profileId.value)
                record.addProperty("configRevision", saved.revision)
                record.remove("localDisplayName")
                val local = record.objectCopy("metadata")
                local.nullableString("localDisplayName", profile.metadata.localDisplayName)
                local.addProperty("sortOrder", profile.metadata.sortOrder)
                local.addProperty("createdAtMillis", profile.metadata.createdAtMillis)
                local.addProperty("updatedAtMillis", profile.metadata.updatedAtMillis)
                record.add("metadata", local)
                record.addProperty("coreToml", saved.document.raw)
                record.addProperty("coreInstanceId", saved.core.instanceId.value)
                if (!record.has("sourceVersion")) record.addProperty("sourceVersion", CORE_SOURCE)
                val policy = record.objectCopy("policy")
                policy.addProperty("includeInternet", saved.vpnPolicy.includeInternet)
                policy.add("dnsServers", strings(saved.vpnPolicy.dnsServers))
                policy.addProperty("ipv6", saved.vpnPolicy.ipv6.name)
                record.add("policy", policy)
                add(record)
            }
        })
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_BYTES) throw ProfileStorageException(ProfileStorageException.Reason.StoreTooLarge)
        return PreparedProfileCommit(ProfileDocument(root, profiles.sortedBy { it.metadata.sortOrder }, target), bytes)
    }

    private fun JsonObject.long(key: String): Long {
        val value = getAsJsonPrimitive(key)
        require(value.isNumber)
        return value.asBigDecimal.longValueExact()
    }
    private fun JsonObject.boolean(key: String): Boolean {
        val value = getAsJsonPrimitive(key)
        require(value.isBoolean)
        return value.asBoolean
    }
    private fun JsonObject.stringOrNull(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
    private fun JsonObject.objectCopy(key: String) = getAsJsonObject(key)?.deepCopy() ?: JsonObject()
    private fun JsonObject.nullableString(key: String, value: String?) { addProperty(key, value) }
    private fun strings(values: List<String>) = JsonArray().apply { values.forEach(::add) }
}
