package com.github.zzwtsy.chuanmen.features.configuration.lib

import com.github.zzwtsy.chuanmen.lib.profile.*
import java.util.Collections
import java.util.UUID

enum class EditorMode { Basic, Full }
enum class DraftField { NetworkName, Secret, Hostname, Peers, Automatic, Ipv4, Mtu, Internet, Dns }

/** 行身份仅在草稿内有效；源索引用于精确复制原节点，不能按 URL 猜测关联。 */
@JvmInline value class PeerRowId(val value: UUID = UUID.randomUUID())
data class PeerDraftRow(val id: PeerRowId = PeerRowId(), val rawUri: String = "", val sourceIndex: Int? = null) {
    override fun toString() = "PeerDraftRow(<redacted>)"
}
sealed interface PeerAction {
    data object Add : PeerAction
    data class Remove(val id: PeerRowId) : PeerAction
    data class ReplaceUri(val id: PeerRowId, val raw: String) : PeerAction {
        override fun toString() = "ReplaceUri(<redacted>)"
    }
    data class Move(val id: PeerRowId, val offset: Int) : PeerAction
}

/** 创建和编辑共用原始输入；缺省密码用 null 表示，显式空密码用空字符串表示。 */
data class RawConfigurationInput(
    val rawNetworkName: String = "", val rawSecret: String? = "", val rawHostname: String = "",
    val rawMtu: String = "1380", val automatic: Boolean = true, val rawIpv4: String = "",
    val internet: Boolean = false, val rawDns: String = "", val peers: List<PeerDraftRow> = emptyList(),
) {
    override fun toString() = "RawConfigurationInput(<redacted>)"
    fun frozen() = copy(peers = Collections.unmodifiableList(peers.toList()))
    fun edit(field: DraftField, raw: String): RawConfigurationInput = when (field) {
        DraftField.NetworkName -> copy(rawNetworkName = raw)
        DraftField.Secret -> copy(rawSecret = raw)
        DraftField.Hostname -> copy(rawHostname = raw)
        DraftField.Mtu -> copy(rawMtu = raw)
        DraftField.Automatic -> copy(automatic = raw == "true")
        DraftField.Ipv4 -> copy(rawIpv4 = raw)
        DraftField.Internet -> copy(internet = raw == "true")
        DraftField.Dns -> copy(rawDns = raw)
        DraftField.Peers -> error("节点必须通过行身份编辑")
    }
    fun editPeer(action: PeerAction): RawConfigurationInput {
        val next = peers.toMutableList()
        when (action) {
            PeerAction.Add -> next.add(PeerDraftRow())
            is PeerAction.Remove -> next.removeAll { it.id == action.id }
            is PeerAction.ReplaceUri -> next.indexOfFirst { it.id == action.id }.takeIf { it >= 0 }?.let { next[it] = next[it].copy(rawUri = action.raw) }
            is PeerAction.Move -> {
                val index = next.indexOfFirst { it.id == action.id }
                val target = index + action.offset
                if (action.offset in listOf(-1, 1) && index >= 0 && target in next.indices) next.add(target, next.removeAt(index))
            }
        }
        return copy(peers = next).frozen()
    }
    fun value(field: DraftField): String = when (field) {
        DraftField.NetworkName -> rawNetworkName
        DraftField.Secret -> rawSecret.orEmpty()
        DraftField.Hostname -> rawHostname
        DraftField.Mtu -> rawMtu
        DraftField.Automatic -> automatic.toString()
        DraftField.Ipv4 -> rawIpv4
        DraftField.Internet -> internet.toString()
        DraftField.Dns -> rawDns
        DraftField.Peers -> error("节点使用独立的行列表")
    }
}

data class InputValidation(val errors: Map<DraftField, String>, val peerErrors: Map<PeerRowId, String>, val value: ValidatedConfigurationInput?)

/** 只由工厂在校验通过后构造；原始秘密及补丁内容不进入默认字符串输出。 */
class ValidatedConfigurationInput internal constructor(
    val input: RawConfigurationInput, val policy: AndroidVpnPolicy,
    val changes: List<CoreConfigChange>, private val address: Ipv4Assignment,
) {
    fun core(identity: CoreInstanceId) = CoreConfig(identity, input.rawNetworkName, input.rawHostname.takeIf(String::isNotEmpty),
        address, input.rawMtu.toInt(), input.rawSecret?.let(NetworkSecret::of), input.peers.map { PeerEndpoint(it.rawUri.trim()) })
    override fun toString() = "ValidatedConfigurationInput(<redacted>)"
}

/** 默认值只用于新建；已有值按源文档投影，未编辑字段不生成补丁。 */
object ConfigurationInputFactory {
    fun forNew() = RawConfigurationInput()
    private fun values(saved: SavedRevision) = RawConfigurationInput(
        saved.core.networkName, saved.core.networkSecret?.revealForCore(), saved.core.hostname.orEmpty(), saved.core.mtu.toString(),
        saved.core.ipv4 == Ipv4Assignment.Automatic, saved.document.staticAddress.orEmpty(), saved.vpnPolicy.includeInternet,
        saved.vpnPolicy.dnsServers.joinToString("\n"),
    )
    fun fromSaved(saved: SavedRevision) = values(saved).copy(peers = saved.document.sourcePeerUris.mapIndexed { index, uri ->
        PeerDraftRow(rawUri = uri, sourceIndex = index)
    }).frozen()

    fun changed(input: RawConfigurationInput, base: SavedRevision): Boolean {
        return input.copy(peers = emptyList()) != values(base) ||
            input.peers.map { it.sourceIndex to it.rawUri } != base.document.sourcePeerUris.mapIndexed { index, uri -> index to uri }
    }

    fun validate(input: RawConfigurationInput, base: SavedRevision? = null): InputValidation {
        val errors = mutableMapOf<DraftField, String>()
        val peerErrors = mutableMapOf<PeerRowId, String>()
        if (input.rawNetworkName.isBlank()) errors[DraftField.NetworkName] = "请输入网络名称"
        val mtu = input.rawMtu.toIntOrNull()
        if (mtu !in 576..9000) errors[DraftField.Mtu] = "MTU 请输入 576–9000 的整数"
        val address = when {
            input.automatic -> Ipv4Assignment.Automatic
            input.rawIpv4.isBlank() -> Ipv4Assignment.Unspecified
            else -> runCatching { Ipv4Assignment.Static(input.rawIpv4.trim()) }.getOrElse {
                errors[DraftField.Ipv4] = "请输入 IPv4 地址及可选的 /0–32 前缀"; Ipv4Assignment.Unspecified
            }
        }
        val origins = mutableSetOf<Int>()
        input.peers.forEach { row ->
            val original = row.sourceIndex?.let { base?.document?.sourcePeerUris?.getOrNull(it) }
            val invalidOrigin = row.sourceIndex != null && (original == null || !origins.add(row.sourceIndex))
            if (invalidOrigin || ((original == null || row.rawUri != original) && runCatching { PeerEndpoint(row.rawUri.trim()) }.isFailure))
                peerErrors[row.id] = "请输入 tcp://host:port 或 udp://host:port"
        }
        if (peerErrors.isNotEmpty()) errors[DraftField.Peers] = "请检查连接节点"
        val policy = runCatching { AndroidVpnPolicy(input.internet, input.rawDns.lines().map(String::trim).filter(String::isNotEmpty)) }.getOrElse {
            errors[DraftField.Dns] = "每行输入一个 IPv4 DNS 地址"; AndroidVpnPolicy()
        }
        if (errors.isNotEmpty()) return InputValidation(errors.toMap(), peerErrors.toMap(), null)
        val changes = buildList {
            if (base == null || input.rawNetworkName != base.core.networkName) add(CoreConfigChange.NetworkName(input.rawNetworkName))
            if (base == null || mtu != base.core.mtu) add(CoreConfigChange.Mtu(requireNotNull(mtu)))
            if (base == null || input.rawSecret != base.core.networkSecret?.revealForCore()) add(CoreConfigChange.Secret(input.rawSecret?.let(NetworkSecret::of)))
            if (base == null || input.rawHostname != base.core.hostname.orEmpty()) add(CoreConfigChange.Hostname(input.rawHostname.takeIf(String::isNotEmpty)))
            if (base == null || input.automatic != (base.core.ipv4 == Ipv4Assignment.Automatic) || (!input.automatic && input.rawIpv4 != base.document.staticAddress.orEmpty())) add(CoreConfigChange.Address(address))
            if (base == null || input.peers.map { it.sourceIndex to it.rawUri } != base.document.sourcePeerUris.mapIndexed { index, uri -> index to uri }) {
                add(CoreConfigChange.PeerEdits(input.peers.map { row ->
                    val index = row.sourceIndex
                    when {
                        index == null -> PeerPatchEntry.Insert(PeerEndpoint(row.rawUri.trim()))
                        row.rawUri == base!!.document.sourcePeerUris[index] -> PeerPatchEntry.Keep(index)
                        else -> PeerPatchEntry.ReplaceUri(index, PeerEndpoint(row.rawUri.trim()))
                    }
                }))
            }
        }
        return InputValidation(emptyMap(), emptyMap(), ValidatedConfigurationInput(input.frozen(), policy, Collections.unmodifiableList(changes), address))
    }

}
