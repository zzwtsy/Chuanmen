package com.github.zzwtsy.chuanmen.lib.profile

import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 连接配置使用的内存仓库，保存和改名都不调用引擎。
 * 同步保护单次修订发布；集合复制后只读，调用方不能事后修改已保存输入。
 * 进程退出后数据消失，不提供 P03 的持久化或进程恢复保证。
 */
class MemoryProfileRepository(initial: List<ProfileSummary>) : ProfileRepository {
    private val saved = initial.associate { it.saved.profileId to freeze(it.saved) }.toMutableMap()
    private val names: MutableMap<LocalProfileId, String?> = initial.associate { it.saved.profileId to it.displayName.trim().takeIf(String::isNotEmpty) }.toMutableMap()
    private val mutableProfiles = MutableStateFlow<List<ProfileSummary>>(emptyList())
    override val profiles = mutableProfiles.asStateFlow()

    init {
        require(saved.size == initial.size) { "配置身份不能重复" }
        publish()
    }

    @Synchronized
    override fun findSaved(profileId: LocalProfileId): SavedRevision? = saved[profileId]

    @Synchronized
    override fun save(profileId: LocalProfileId, core: CoreConfig, policy: AndroidVpnPolicy): SavedRevision? {
        val old = saved[profileId] ?: return null
        require(core.instanceId == old.core.instanceId) { "保存不能更换核心实例身份" }
        val next = freeze(SavedRevision(profileId, Math.addExact(old.revision, 1), core, policy))
        saved[profileId] = next
        publish()
        return next
    }

    @Synchronized
    override fun rename(profileId: LocalProfileId, localDisplayName: String?): Boolean {
        if (profileId !in saved) return false
        names[profileId] = localDisplayName?.trim()?.takeIf { it.isNotEmpty() }
        publish()
        return true
    }

    private fun publish() {
        mutableProfiles.value = Collections.unmodifiableList(saved.values.map {
            ProfileSummary(it, names[it.profileId] ?: it.core.networkName)
        })
    }

    private fun freeze(saved: SavedRevision): SavedRevision = saved.copy(
        core = saved.core.copy(peers = Collections.unmodifiableList(saved.core.peers.toList())),
        vpnPolicy = saved.vpnPolicy.copy(dnsServers = Collections.unmodifiableList(saved.vpnPolicy.dnsServers.toList())),
    )
}
