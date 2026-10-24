package com.github.zzwtsy.chuanmen.features.configuration.lib

import com.github.zzwtsy.chuanmen.lib.profile.*
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

@JvmInline value class DraftEpoch(val value: UUID = UUID.randomUUID())
@JvmInline value class SaveToken(val value: UUID = UUID.randomUUID())
sealed interface DraftSaveState {
    data object Idle : DraftSaveState
    data class Saving(val token: SaveToken) : DraftSaveState
    data object Conflict : DraftSaveState
}

/** 草稿只在 Activity 内存中保存；状态与原始输入分别建模，不承载磁盘或会话操作。 */
data class Draft(
    val base: SavedRevision,
    val input: RawConfigurationInput = ConfigurationInputFactory.fromSaved(base),
    val mode: EditorMode = EditorMode.Basic,
    val search: String = "",
    val epoch: DraftEpoch = DraftEpoch(),
    val editSequence: Long = 0,
    val saveState: DraftSaveState = DraftSaveState.Idle,
    val unacknowledgedEdit: Boolean = false,
    val validation: InputValidation = ConfigurationInputFactory.validate(input, base),
) {
    val dirty get() = unacknowledgedEdit || ConfigurationInputFactory.changed(input, base)
    val saving get() = saveState is DraftSaveState.Saving
    val conflict get() = saveState == DraftSaveState.Conflict
    val errors get() = validation.errors
    val rawMtu get() = input.rawMtu
    val rawIpv4 get() = input.rawIpv4
    fun validate() = copy(validation = ConfigurationInputFactory.validate(input, base))
    override fun toString() = "Draft(<redacted>, editSequence=$editSequence)"
}

/** 保存命令捕获完整基准和有效输入；令牌识别请求，代次与序号识别后续编辑。 */
class SaveSubmission internal constructor(val token: SaveToken, val draft: Draft, val value: ValidatedConfigurationInput) {
    val profileId get() = draft.base.profileId
    override fun toString() = "SaveSubmission(<redacted>)"
}

/** 主线程局部归约器；生产 I/O 只有 ViewModel 的保存入口，不存在第二条测试专用保存路径。 */
class ConfigurationEditor {
    private val mutableDrafts = MutableStateFlow<Map<LocalProfileId, Draft>>(emptyMap())
    val drafts = mutableDrafts.asStateFlow()
    fun open(saved: SavedRevision) {
        if (saved.profileId !in mutableDrafts.value) mutableDrafts.value += saved.profileId to Draft(saved)
    }
    fun edit(id: LocalProfileId, field: DraftField, raw: String) = editInput(id) { it.edit(field, raw) }
    fun peer(id: LocalProfileId, action: PeerAction) = editInput(id) { it.editPeer(action) }
    private fun editInput(id: LocalProfileId, transform: (RawConfigurationInput) -> RawConfigurationInput) = update(id) { old ->
        val input = transform(old.input)
        if (input == old.input) old else old.copy(input = input,
            epoch = if (old.editSequence == Long.MAX_VALUE) DraftEpoch() else old.epoch,
            editSequence = if (old.editSequence == Long.MAX_VALUE) 0 else old.editSequence + 1).validate()
    }
    fun mode(id: LocalProfileId, mode: EditorMode) = update(id) { it.copy(mode = mode) }
    fun search(id: LocalProfileId, raw: String) = update(id) { it.copy(search = raw) }
    fun forget(id: LocalProfileId) { mutableDrafts.value -= id }
    private fun update(id: LocalProfileId, transform: (Draft) -> Draft) {
        mutableDrafts.value[id]?.let { mutableDrafts.value += id to transform(it) }
    }
    fun beginSave(id: LocalProfileId): SaveSubmission? {
        val current = mutableDrafts.value[id] ?: return null
        if (current.saveState != DraftSaveState.Idle) return null
        val checked = current.validate()
        mutableDrafts.value += id to checked
        val value = checked.validation.value ?: return null
        val submission = SaveSubmission(SaveToken(), checked, value)
        update(id) { it.copy(saveState = DraftSaveState.Saving(submission.token)) }
        return submission
    }
    private fun matches(current: Draft, submission: SaveSubmission) =
        (current.saveState as? DraftSaveState.Saving)?.token == submission.token

    fun acceptSaved(submission: SaveSubmission, saved: SavedRevision) = update(submission.profileId) { current ->
        if (saved.profileId != submission.profileId || !matches(current, submission)) return@update current
        if (current.epoch == submission.draft.epoch && current.editSequence == submission.draft.editSequence) {
            val clean = ConfigurationInputFactory.fromSaved(saved)
            val peers = clean.peers.mapIndexed { index, row -> row.copy(id = current.input.peers[index].id) }
            Draft(saved, input = clean.copy(peers = peers).frozen(), mode = current.mode, search = current.search,
                epoch = current.epoch, editSequence = current.editSequence)
        } else {
            val indices = submission.value.input.peers.mapIndexed { index, row -> row.id to index }.toMap()
            current.copy(base = saved, input = current.input.copy(peers = current.input.peers.map { row ->
                row.copy(sourceIndex = indices[row.id])
            }).frozen(), saveState = DraftSaveState.Idle, unacknowledgedEdit = true).validate()
        }
    }
    fun reject(submission: SaveSubmission, error: WriteError) = update(submission.profileId) { current ->
        if (!matches(current, submission)) current else current.copy(saveState =
            if (error == WriteError.Conflict) DraftSaveState.Conflict else DraftSaveState.Idle)
    }
    fun finish(submission: SaveSubmission) = update(submission.profileId) { current ->
        if (matches(current, submission)) current.copy(saveState = DraftSaveState.Idle) else current
    }
    /** 保存中拒绝重置；重置后的新代次不能接受之前请求的回执。 */
    fun discard(saved: SavedRevision): Boolean {
        val current = mutableDrafts.value[saved.profileId] ?: return false
        if (current.saving) return false
        mutableDrafts.value += saved.profileId to Draft(saved, mode = current.mode, search = current.search)
        return true
    }
}
