package com.github.zzwtsy.chuanmen.feature.configuration.storage

import com.github.zzwtsy.chuanmen.contracts.AndroidVpnPolicy
import com.github.zzwtsy.chuanmen.contracts.CoreConfig
import com.github.zzwtsy.chuanmen.contracts.LocalProfileId
import com.github.zzwtsy.chuanmen.contracts.ProfileRepository
import com.github.zzwtsy.chuanmen.contracts.ProfileSummary
import com.github.zzwtsy.chuanmen.contracts.SavedRevision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 配置切片的私有元数据；空名称使用当前网络名称显示，不写入核心身份。 */
private data class LocalProfileMetadata(val profileId: LocalProfileId, val localDisplayName: String?)

/** demo 专用内存仓库；每次发布新列表，名称更新不增加核心配置修订。 */
class DemoProfileRepository(initial: List<ProfileSummary>) : ProfileRepository {
    private val saved = initial.associate { it.saved.profileId to it.saved }.toMutableMap()
    private val metadata = initial.associate {
        it.saved.profileId to LocalProfileMetadata(it.saved.profileId, it.displayName)
    }.toMutableMap()
    private val mutableProfiles = MutableStateFlow(initial.toList())
    override val profiles = mutableProfiles.asStateFlow()

    override fun findSaved(profileId: LocalProfileId): SavedRevision? = saved[profileId]

    override fun save(
        profileId: LocalProfileId,
        core: CoreConfig,
        policy: AndroidVpnPolicy,
    ): SavedRevision? {
        val old = saved[profileId] ?: return null
        // 实例身份由本地配置稳定映射，表单恢复和本地改名均不能接管另一个实例。
        require(core.instanceId == old.core.instanceId)
        val next = SavedRevision(profileId, old.revision + 1, core, policy)
        saved[profileId] = next
        publish()
        return next
    }

    override fun rename(profileId: LocalProfileId, localDisplayName: String?): Boolean {
        if (profileId !in saved) return false
        metadata[profileId] = LocalProfileMetadata(
            profileId, localDisplayName?.trim()?.takeIf(String::isNotEmpty),
        )
        publish()
        return true
    }

    private fun publish() {
        mutableProfiles.value = saved.values.map { revision ->
            ProfileSummary(revision, metadata[revision.profileId]?.localDisplayName ?: revision.core.networkName)
        }
    }
}
