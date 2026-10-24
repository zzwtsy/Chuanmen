package com.github.zzwtsy.chuanmen.lib.profile

import dev.eav.tomlkt.*

/**
 * JVM 状态测试的配置适配，仅模拟已接入字段投影，不代替 Rust 固定核心验收。
 * 使用测试专用 TOML 读写器保留文档，不在正式应用放置解析替身。
 */
class TestCoreConfigCodec : CoreConfigCodec {
    override suspend fun parse(raw: String, identity: CoreInstanceId): ConfigResult<CoreConfigDocument> = try {
        ConfigResult.Success(document(raw, identity))
    } catch (_: Exception) { ConfigResult.Failure(ConfigProblem(ConfigProblemCode.Syntax)) }

    override suspend fun create(core: CoreConfig): ConfigResult<CoreConfigDocument> = ConfigResult.Success(core.testDocument())

    override suspend fun patch(document: CoreConfigDocument, changes: List<CoreConfigChange>): ConfigResult<CoreConfigDocument> {
        if (changes.isEmpty()) return ConfigResult.Success(document)
        var table = Toml.parseToTomlTable(document.raw)
        fun put(path: String, value: TomlElement?) {
            val parts = path.split('.')
            val root = table.content.toMutableMap()
            if (parts.size == 1) { if (value == null) root.remove(path) else root[path] = value }
            else {
                val nested = (root[parts[0]] as? TomlTable)?.content.orEmpty().toMutableMap()
                if (value == null) nested.remove(parts[1]) else nested[parts[1]] = value
                root[parts[0]] = TomlTable(nested)
            }
            table = TomlTable(root)
        }
        changes.forEach { change -> when (change) {
            is CoreConfigChange.NetworkName -> put("network_identity.network_name", TomlLiteral(change.value))
            is CoreConfigChange.Secret -> put("network_identity.network_secret", change.value?.let { TomlLiteral(it.revealForCore()) })
            is CoreConfigChange.Hostname -> put("hostname", change.value?.let(::TomlLiteral))
            is CoreConfigChange.Mtu -> put("flags.mtu", TomlLiteral(change.value))
            is CoreConfigChange.PeerEdits -> {
                val old = (table["peer"] as? TomlArray).orEmpty()
                val used = mutableSetOf<Int>()
                val next = change.entries.map { entry ->
                    val index = when (entry) { is PeerPatchEntry.Keep -> entry.sourceIndex; is PeerPatchEntry.ReplaceUri -> entry.sourceIndex; is PeerPatchEntry.Insert -> null }
                    if (index != null) require(index in old.indices && used.add(index))
                    val fields = index?.let { (old[it] as TomlTable).content.toMutableMap() } ?: mutableMapOf()
                    when (entry) {
                        is PeerPatchEntry.Keep -> Unit
                        is PeerPatchEntry.ReplaceUri -> fields["uri"] = TomlLiteral(entry.uri.value)
                        is PeerPatchEntry.Insert -> fields["uri"] = TomlLiteral(entry.uri.value)
                    }
                    TomlTable(fields)
                }
                put("peer", TomlArray(next))
            }
            is CoreConfigChange.Address -> {
                put("dhcp", TomlLiteral(change.value == Ipv4Assignment.Automatic))
                (change.value as? Ipv4Assignment.Static)?.let { put("ipv4", TomlLiteral(it.address)) }
                if (change.value == Ipv4Assignment.Unspecified) put("ipv4", null)
            }
        } }
        return parse(Toml.encodeToString(TomlTable.serializer(), table), document.core.instanceId)
    }

    override suspend fun encodeForRuntime(document: CoreConfigDocument): ConfigResult<RuntimeCoreConfig> =
        ConfigResult.Success(RuntimeCoreConfig(document.raw, emptyList()))

    companion object {
        internal fun document(raw: String, identity: CoreInstanceId): CoreConfigDocument {
            val table = Toml.parseToTomlTable(raw)
            val network = table["network_identity"] as? TomlTable
            val flags = table["flags"] as? TomlTable
            fun TomlElement?.text() = (this as? TomlLiteral)?.content
            val address = table["ipv4"].text()
            val core = CoreConfig(identity, network?.get("network_name").text() ?: "default", table["hostname"].text(),
                if (table["dhcp"].text() == "true") Ipv4Assignment.Automatic else if (address != null) Ipv4Assignment.Static(address) else Ipv4Assignment.Unspecified,
                flags?.get("mtu").text()?.toInt() ?: 1380, network?.get("network_secret").text()?.let(NetworkSecret::of),
                (table["peer"] as? TomlArray).orEmpty().map { PeerEndpoint((it as TomlTable)["uri"].text()!!) })
            return CoreConfigDocument(raw, core, table.toString(), emptyList(), staticAddress = address)
        }
    }
}

/** 合成只读文档夹具；生产消费者必须通过 NativeCoreConfigCodec 创建或修改文档。 */
fun CoreConfig.testDocument(): CoreConfigDocument {
    val root = linkedMapOf<String, Any>("dhcp" to (ipv4 == Ipv4Assignment.Automatic),
        "network_identity" to buildMap<String, String> {
            put("network_name", networkName)
            networkSecret?.let { put("network_secret", it.revealForCore()) }
        }, "flags" to mapOf("mtu" to mtu), "peer" to peers.map { mapOf("uri" to it.value) })
    hostname?.let { root["hostname"] = it }
    (ipv4 as? Ipv4Assignment.Static)?.let { root["ipv4"] = it.address }
    return TestCoreConfigCodec.document(Toml.encodeToString(TomlTable.serializer(), TomlTable(root)), instanceId)
}

/** 将既有状态测试的值输入转成文档夹具；此工厂只存在 test source set。 */
fun SavedRevision(profileId: LocalProfileId, revision: Long, core: CoreConfig, vpnPolicy: AndroidVpnPolicy): SavedRevision =
    SavedRevision(profileId, revision, core.testDocument(), vpnPolicy)
