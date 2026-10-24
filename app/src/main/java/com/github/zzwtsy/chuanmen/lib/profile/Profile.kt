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
    data object Unspecified : Ipv4Assignment
    data object Automatic : Ipv4Assignment
    data class Static(val address: String) : Ipv4Assignment {
        init {
            val parts = address.split('/')
            require(parts.size in 1..2 && isIpv4Literal(parts[0]) &&
                (parts.size == 1 || (parts[1].toIntOrNull() in 0..32 && parts[1].toIntOrNull().toString() == parts[1]))
            ) { "静态地址必须是 IPv4 字面量及可选前缀" }
        }
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
            return NetworkSecret(value)
        }
    }
}

/** P03 首批只编辑 TCP/UDP 对端；拒绝 URL 凭据、查询和片段，错误不回显输入。 */
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
 * 已接入字段的只读投影，不是 EasyTier SDK 或完整 TOML schema。
 *
 * [instanceId] 独立于本地显示名称；保存与修改必须经过文档 codec。
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

/** 当前明确阻断 IPv6；完整双栈能力另行实现和验证。 */
enum class Ipv6Policy { Block }

/** 平台路由与核心输入分开；自定义 DNS 只接受 IPv4 字面量，不注入默认服务器。 */
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
    val document: CoreConfigDocument,
    val vpnPolicy: AndroidVpnPolicy,
) {
    val core: CoreConfig get() = document.core
    init {
        require(revision > 0)
    }
}

/** 本地元数据不进入核心配置；排序和时间也不增加核心修订。 */
data class LocalProfileMetadata(
    val localDisplayName: String? = null,
    val sortOrder: Long = 0,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = createdAtMillis,
)

/** 面向跨切片消费者的只读摘要；显示名由独立元数据生成。 */
data class ProfileSummary(
    val saved: SavedRevision,
    val displayName: String,
    val metadata: LocalProfileMetadata = LocalProfileMetadata(displayName.trim().takeIf(String::isNotEmpty)),
)

/**
 * 配置操作的窄边界。实现负责保存与名称更新，不调用会话或引擎。
 *
 * [save] 核对提交基准并只在内容改变时递增修订；[rename] 只修改本地元数据。
 * 写入在仓库后台队列执行，成功落盘才发布新状态；调用方通过结果区分失败原因。
 * 删除前调用方须先与会话协调器确认配置没有运行或待处理操作。
 */
interface ProfileRepository {
    val state: StateFlow<RepositoryState>
    val profiles: StateFlow<List<ProfileSummary>>
    suspend fun load()
    fun findSaved(profileId: LocalProfileId): SavedRevision?
    suspend fun save(profileId: LocalProfileId, document: CoreConfigDocument, policy: AndroidVpnPolicy, expectedRevision: Long): WriteResult<SavedRevision>
    suspend fun rename(profileId: LocalProfileId, localDisplayName: String?): WriteResult<Unit>
    suspend fun create(document: CoreConfigDocument, policy: AndroidVpnPolicy, localDisplayName: String? = null): WriteResult<SavedRevision>
    val lastConnectionTarget: LocalProfileId?
    suspend fun rememberConnection(saved: SavedRevision): WriteResult<Unit>
    suspend fun delete(profileId: LocalProfileId): WriteResult<Unit>
}

/** 读取失败与首次空列表分开；Service 只在 Ready 后接受配置命令。 */
sealed interface RepositoryState {
    data object Loading : RepositoryState
    data object Ready : RepositoryState
    data class ReadFailed(val reason: ProfileStorageException.Reason) : RepositoryState
}

/** 所有写入结果显式分类，不把版本冲突、校验失败和磁盘失败压成 null。 */
sealed interface WriteResult<out T> {
    data class Success<T>(val value: T) : WriteResult<T>
    data class Failure(val error: WriteError) : WriteResult<Nothing>
}

enum class WriteError {
    ValidationError, Conflict, ReadUnavailable, WriteFailed, KeyUnavailable, NotFound, RevisionExhausted, StoreTooLarge, DocumentTooLarge,
}

/** Service 只依赖共享仓库契约，不依赖具体 Application 装配类。 */
interface ProfileRepositoryOwner { val profiles: ProfileRepository }
