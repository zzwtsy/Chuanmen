package com.github.zzwtsy.chuanmen.contracts

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
    data class Static(val address: String) : Ipv4Assignment
}

/**
 * P01 实际使用的最小核心输入，不是 EasyTier SDK 或完整 TOML schema。
 *
 * [instanceId] 独立于本地显示名称；完整字段和核心解析在 P03 按选定来源扩展。
 */
data class CoreConfig(
    val instanceId: CoreInstanceId,
    val networkName: String,
    val hostname: String?,
    val ipv4: Ipv4Assignment = Ipv4Assignment.Automatic,
    val mtu: Int = 1380,
) {
    init {
        require(networkName.isNotBlank())
        require(mtu in 576..9000)
    }
}

/** 平台路由意图，与核心输入分开；P01 不将其序列化成核心配置或建立 TUN。 */
data class AndroidVpnPolicy(val includeInternet: Boolean = false)

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
 * P01 的实现是 demo 内存仓库，不提供磁盘持久化保证。
 */
interface ProfileRepository {
    val profiles: StateFlow<List<ProfileSummary>>
    fun findSaved(profileId: LocalProfileId): SavedRevision?
    fun save(profileId: LocalProfileId, core: CoreConfig, policy: AndroidVpnPolicy): SavedRevision?
    fun rename(profileId: LocalProfileId, localDisplayName: String?): Boolean
}
