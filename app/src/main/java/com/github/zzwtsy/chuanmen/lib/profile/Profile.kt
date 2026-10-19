package com.github.zzwtsy.chuanmen.lib.profile

import com.github.zzwtsy.chuanmen.utils.isIpv4Literal
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow

/** 本地配置的永久主键；显示名称、核心实例标识和会话代次不参与其相等性。 */
@JvmInline
value class LocalProfileId(val value: String) {
    init {
        require(UUID.fromString(value).toString() == value) { "profileId 必须是规范 UUID" }
    }
}

/** 由装配层稳定分配的核心实例身份，不能由可修改的本地名称生成。 */
@JvmInline
value class CoreInstanceId(val value: String) {
    init {
        require(value.isNotBlank())
    }
}

/** 地址意图；进入基础编辑模式不会改变已有静态地址。 */
sealed interface Ipv4Assignment {
    data object Automatic : Ipv4Assignment
    data class Static(val address: String) : Ipv4Assignment {
        init { require(isIpv4Literal(address)) { "静态地址必须是 IPv4 字面量" } }
    }
}

/** 网络秘密只在序列化到核心时显式读取；嵌套配置、命令和快照的字符串输出均脱敏。 */
class NetworkSecret private constructor(private val content: String) {
    fun revealForCore(): String = content
    override fun toString(): String = "<redacted>"
    override fun equals(other: Any?): Boolean = other is NetworkSecret && content == other.content
    override fun hashCode(): Int = content.hashCode()

    companion object {
        fun of(value: String): NetworkSecret {
            require(value.isNotEmpty()) { "网络密码不能为空" }
            return NetworkSecret(value)
        }
    }
}

/** P02 受控入口只接受 TCP/UDP 对端；拒绝 URL 凭据、查询和片段，错误不回显输入。 */
data class PeerEndpoint(val value: String) {
    init {
        val uri = try { URI(value) } catch (_: Exception) { null }
        require(uri != null && uri.scheme in setOf("tcp", "udp") &&
            !uri.host.isNullOrBlank() && uri.port in 1..65535 && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.isNullOrEmpty()
        ) { "对端必须是无凭据的 tcp://host:port 或 udp://host:port" }
    }
}

/**
 * P02 受控连接的最小核心输入，不是 EasyTier SDK 或完整 TOML schema。
 *
 * [instanceId] 独立于本地显示名称；完整字段和核心解析在 P03 按选定来源扩展。
 */
data class CoreConfig(
    val instanceId: CoreInstanceId,
    val networkName: String,
    val hostname: String?,
    val ipv4: Ipv4Assignment = Ipv4Assignment.Automatic,
    val mtu: Int = 1380,
    val networkSecret: NetworkSecret? = null,
    val peers: List<PeerEndpoint> = emptyList(),
) {
    init {
        require(networkName.isNotBlank())
        require(mtu in 576..9000)
    }
}

/** P02 明确阻断 IPv6；完整双栈能力另行实现和验证。 */
enum class Ipv6Policy { Block }

/** 平台路由与核心输入分开；DNS 必须来自受控解析服务，不能默认伪造可用值。 */
data class AndroidVpnPolicy(
    val includeInternet: Boolean = false,
    val dnsServers: List<String> = emptyList(),
    val ipv6: Ipv6Policy = Ipv6Policy.Block,
) {
    init { require(dnsServers.all(::isIpv4Literal)) { "DNS 必须是 IPv4 字面量" } }
}

/** 一次已保存的版本；仅保存此值不能更新实际会话快照。 */
data class SavedRevision(
    val profileId: LocalProfileId,
    val revision: Long,
    val core: CoreConfig,
    val vpnPolicy: AndroidVpnPolicy,
) {
    init {
        require(revision > 0)
    }
}

/** 面向跨切片消费者的只读摘要；私有元数据与草稿留在配置切片。 */
data class ProfileSummary(val saved: SavedRevision, val displayName: String)

/**
 * 配置操作的窄边界。实现负责保存与名称更新，不调用会话或引擎。
 *
 * [save] 成功后返回新修订；[rename] 只修改本地元数据，缺少配置时返回 false。
 * 当前实现是受控内存仓库，不提供磁盘持久化保证。
 */
interface ProfileRepository {
    val profiles: StateFlow<List<ProfileSummary>>
    fun findSaved(profileId: LocalProfileId): SavedRevision?
    fun save(profileId: LocalProfileId, core: CoreConfig, policy: AndroidVpnPolicy): SavedRevision?
    fun rename(profileId: LocalProfileId, localDisplayName: String?): Boolean
}

/** Service 只依赖共享仓库契约，不依赖具体 Application 装配类。 */
interface ProfileRepositoryOwner { val profiles: ProfileRepository }
