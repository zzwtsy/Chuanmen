package com.github.zzwtsy.chuanmen.features.configuration.lib

import com.github.zzwtsy.chuanmen.lib.profile.CoreConfig
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.ProfileRepository
import com.github.zzwtsy.chuanmen.lib.profile.SavedRevision
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class EditorMode { Basic, Full }
enum class DraftField { NetworkName, Mtu }

/** 无效输入仍保留 raw 值；模式和错误属于同一份按配置隔离的草稿。 */
data class Draft(
    val base: SavedRevision,
    val rawNetworkName: String = base.core.networkName,
    val rawMtu: String = base.core.mtu.toString(),
    val mode: EditorMode = EditorMode.Basic,
    val errors: Map<DraftField, String> = emptyMap(),
) {
    val dirty: Boolean
        get() = rawNetworkName != base.core.networkName || rawMtu != base.core.mtu.toString()

    /** 校验 P01 的最小字段；不声称完成 EasyTier 核心解析或全部配置校验。 */
    fun validate(): Draft = copy(errors = buildMap {
        if (rawNetworkName.isBlank()) put(DraftField.NetworkName, "请输入网络名称")
        if (rawMtu.toIntOrNull() !in 576..9000) put(DraftField.Mtu, "MTU 请输入 576–9000 的整数")
    })

    fun validatedCore(): CoreConfig? {
        if (validate().errors.isNotEmpty()) return null
        return base.core.copy(networkName = rawNetworkName.trim(), mtu = rawMtu.toInt())
    }
}

/**
 * 编辑状态只调用仓库；保存、丢弃和恢复实际配置不会发出任何会话动作。
 *
 * 基础/完整共用 [drafts]，已有静态地址与平台策略通过 base 保留。
 */
class ConfigurationEditor(private val repository: ProfileRepository) {
    private val mutableDrafts = MutableStateFlow<Map<LocalProfileId, Draft>>(emptyMap())
    val drafts = mutableDrafts.asStateFlow()

    fun open(profileId: LocalProfileId) {
        if (profileId in mutableDrafts.value) return
        repository.findSaved(profileId)?.let { saved ->
            mutableDrafts.value += profileId to Draft(saved)
        }
    }

    fun edit(profileId: LocalProfileId, field: DraftField, raw: String) {
        val old = mutableDrafts.value[profileId] ?: return
        val next = when (field) {
            DraftField.NetworkName -> old.copy(rawNetworkName = raw)
            DraftField.Mtu -> old.copy(rawMtu = raw)
        }.validate()
        mutableDrafts.value += profileId to next
    }

    fun mode(profileId: LocalProfileId, mode: EditorMode) {
        val old = mutableDrafts.value[profileId] ?: return
        mutableDrafts.value += profileId to old.copy(mode = mode)
    }

    fun save(profileId: LocalProfileId): SavedRevision? {
        val draft = mutableDrafts.value[profileId]?.validate() ?: return null
        mutableDrafts.value += profileId to draft
        val core = draft.validatedCore() ?: return null
        val saved = repository.save(profileId, core, draft.base.vpnPolicy) ?: return null
        mutableDrafts.value += profileId to Draft(saved, mode = draft.mode)
        return saved
    }

    /** 丢弃必须由显式动作调用；不会修改本地名称。 */
    fun discard(profileId: LocalProfileId) {
        val saved = repository.findSaved(profileId) ?: return
        val mode = mutableDrafts.value[profileId]?.mode ?: EditorMode.Basic
        mutableDrafts.value += profileId to Draft(saved, mode = mode)
    }

    /**
     * 将实际会话配置另存为新修订，保留本地名称，不更新 applied 或发出会话动作。
     * 恢复后是否待应用由已保存内容与实际配置的差异决定，不由修订号决定。
     */
    fun restore(applied: SavedRevision): SavedRevision? {
        val saved = repository.save(applied.profileId, applied.core, applied.vpnPolicy) ?: return null
        val mode = mutableDrafts.value[applied.profileId]?.mode ?: EditorMode.Basic
        mutableDrafts.value += applied.profileId to Draft(saved, mode = mode)
        return saved
    }
}
