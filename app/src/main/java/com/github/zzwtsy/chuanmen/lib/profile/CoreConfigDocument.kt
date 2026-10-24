package com.github.zzwtsy.chuanmen.lib.profile

/**
 * 原始 TOML 是配置源，core 只是固定核心解析后的只读投影。
 * 原文只供 codec 与加密存储读取；普通字符串输出和 UI 状态不展开秘密。
 */
class CoreConfigDocument internal constructor(
    internal val raw: String,
    core: CoreConfig,
    private val semantic: String,
    unsupportedFields: List<String>,
    private val runtimeSemantic: String = semantic,
    val staticAddress: String? = (core.ipv4 as? Ipv4Assignment.Static)?.address,
    sourcePeerUris: List<String> = core.peers.map { it.value },
) {
    val core: CoreConfig = core.copy(peers = java.util.Collections.unmodifiableList(core.peers.toList()))
    val sourcePeerUris: List<String> = java.util.Collections.unmodifiableList(sourcePeerUris.toList())
    val unsupportedFields: List<String> = java.util.Collections.unmodifiableList(unsupportedFields.toList())
    fun sameValues(other: CoreConfigDocument): Boolean = core.instanceId == other.core.instanceId && semantic == other.semantic
    fun sameRuntimeValues(other: CoreConfigDocument): Boolean = core.instanceId == other.core.instanceId && runtimeSemantic == other.runtimeSemantic
    internal fun withIdentity(identity: CoreInstanceId) = CoreConfigDocument(raw, core.copy(instanceId = identity), semantic, unsupportedFields, runtimeSemantic, staticAddress, sourcePeerUris)
    override fun toString() = "CoreConfigDocument(<redacted>)"
    override fun equals(other: Any?) = other is CoreConfigDocument && raw == other.raw && core.instanceId == other.core.instanceId
    override fun hashCode() = 31 * raw.hashCode() + core.instanceId.hashCode()
    companion object { const val MAX_BYTES = 128 * 1024 }
}

/** 只接受本阶段已接入字段；UI 不构造原生 JSON 或拼接 TOML。 */
sealed interface CoreConfigChange {
    data class NetworkName(val value: String) : CoreConfigChange
    data class Secret(val value: NetworkSecret?) : CoreConfigChange
    data class Hostname(val value: String?) : CoreConfigChange
    data class Address(val value: Ipv4Assignment) : CoreConfigChange
    data class PeerEdits(val entries: List<PeerPatchEntry>) : CoreConfigChange
    data class Mtu(val value: Int) : CoreConfigChange
}

/** 条目顺序就是结果顺序；每个旧索引最多引用一次，未引用的节点由用户明确删除。 */
sealed interface PeerPatchEntry {
    data class Keep(val sourceIndex: Int) : PeerPatchEntry
    data class ReplaceUri(val sourceIndex: Int, val uri: PeerEndpoint) : PeerPatchEntry
    data class Insert(val uri: PeerEndpoint) : PeerPatchEntry
}

/** 可运行投影仍包含秘密，只在核心启动边界显式读取。 */
class RuntimeCoreConfig internal constructor(internal val toml: String, val overriddenFields: List<String>) {
    override fun toString() = "RuntimeCoreConfig(<redacted>)"
}

/** 原生解析错误不含输入；位置是 UTF-8 字节偏移，缺省表示核心未提供安全定位。 */
data class ConfigProblem(
    val code: ConfigProblemCode,
    val fieldKey: String? = null,
    val byteOffset: Int? = null,
    val unsupportedFields: List<String> = emptyList(),
)
enum class ConfigProblemCode { Syntax, InvalidConfig, UnsupportedField, DocumentTooLarge, RequestTooLarge, NativeUnavailable, Protocol }
sealed interface ConfigResult<out T> {
    data class Success<T>(val value: T) : ConfigResult<T>
    data class Failure(val problem: ConfigProblem) : ConfigResult<Nothing>
}

/** parse/patch 不创建会话；运行投影对未接入字段返回差异，不能静默删除后启动。 */
interface CoreConfigCodec {
    suspend fun parse(raw: String, identity: CoreInstanceId): ConfigResult<CoreConfigDocument>
    suspend fun create(core: CoreConfig): ConfigResult<CoreConfigDocument>
    suspend fun patch(document: CoreConfigDocument, changes: List<CoreConfigChange>): ConfigResult<CoreConfigDocument>
    suspend fun preview(document: CoreConfigDocument): ConfigResult<ConfigPreview> = when (val result = encodeForRuntime(document)) {
        is ConfigResult.Failure -> result
        is ConfigResult.Success -> ConfigResult.Success(ConfigPreview(result.value.overriddenFields))
    }
    suspend fun encodeForRuntime(document: CoreConfigDocument): ConfigResult<RuntimeCoreConfig>
}

/** 内部预览只列参数差异，不展开任何原始值或秘密。 */
data class ConfigPreview(val overriddenFields: List<String>)
