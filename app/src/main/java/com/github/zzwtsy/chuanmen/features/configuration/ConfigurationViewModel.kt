package com.github.zzwtsy.chuanmen.features.configuration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.zzwtsy.chuanmen.features.configuration.lib.*
import com.github.zzwtsy.chuanmen.lib.profile.*
import com.github.zzwtsy.chuanmen.lib.vpn.OperationResult
import com.github.zzwtsy.chuanmen.lib.vpn.SessionController
import com.github.zzwtsy.chuanmen.lib.vpn.userMessage
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** 唯一草稿所有者；请求捕获身份，页面重组和切换不拥有仓库或真实会话。 */
class ConfigurationViewModel(
    private val profiles: ProfileRepository,
    private val sessions: SessionController,
    private val viewedProfileId: StateFlow<LocalProfileId?>,
    private val codec: CoreConfigCodec,
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ViewModel() {
    private val editor = ConfigurationEditor()
    private val message = MutableStateFlow<String?>(null)
    private var activeId = viewedProfileId.value
    private val mutableCreation = MutableStateFlow<CreationDraft?>(null)
    val creation = mutableCreation.asStateFlow()

    init {
        currentId()
        viewModelScope.launch { viewedProfileId.collect { currentId() } }
    }
    val state = combine(profiles.profiles, sessions.state, viewedProfileId, editor.drafts, message) { saved, session, id, drafts, notice ->
        ConfigurationUiState.project(saved, session, id, drafts[id], notice.takeIf { id == activeId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConfigurationUiState.project(
        profiles.profiles.value, sessions.state.value, activeId, editor.drafts.value[activeId],
    ))

    fun act(action: ConfigurationAction) {
        val id = currentId() ?: return
        when (action) {
            is ConfigurationAction.Edit -> editor.edit(id, action.field, action.raw)
            is ConfigurationAction.Peer -> editor.peer(id, action.action)
            is ConfigurationAction.Search -> editor.search(id, action.raw)
            is ConfigurationAction.ChangeMode -> editor.mode(id, action.mode)
            is ConfigurationAction.Rename -> write(id) { profiles.rename(id, action.name).notice() }
            ConfigurationAction.Save -> save(id)
            ConfigurationAction.Apply -> if (editor.drafts.value[id]?.let { !it.dirty && !it.saving && !it.conflict } == true)
                profiles.findSaved(id)?.let { report(sessions.apply(it)) }
            ConfigurationAction.Discard, ConfigurationAction.Reload -> profiles.findSaved(id)?.let(editor::discard)
            ConfigurationAction.Restore -> if (sessions.state.value.applied?.saved?.profileId == id) report(sessions.restoreRunning())
            ConfigurationAction.KeepDraft -> message.value = "草稿已保留；重新载入前不能覆盖已保存版本"
            ConfigurationAction.Query -> if (sessions.state.value.requestedProfileId == id) report(sessions.query())
            ConfigurationAction.Stop -> if (sessions.state.value.requestedProfileId == id) report(sessions.disconnect())
            ConfigurationAction.Delete -> if (editor.drafts.value[id]?.saving != true) write(id) {
                when (sessions.deleteProfile(id)) {
                    is WriteResult.Success -> { editor.forget(id); "已删除配置" }
                    is WriteResult.Failure -> "尚未确认停止或无法删除，配置已保留"
                }
            }
            ConfigurationAction.ClearMessage -> message.value = null
        }
    }

    private fun save(id: LocalProfileId) {
        val submission = editor.beginSave(id)
        if (submission == null) {
            if (editor.drafts.value[id]?.errors?.isNotEmpty() == true) message.value = "请检查输入错误"
            return
        }
        viewModelScope.launch(writeDispatcher) {
            try {
                val document = codec.patch(submission.draft.base.document, submission.value.changes)
                val notice = when (document) {
                    is ConfigResult.Failure -> "配置解析失败，输入已保留".also { editor.reject(submission, WriteError.ValidationError, it) }
                    is ConfigResult.Success -> when (val result = profiles.save(id, document.value, submission.value.policy, submission.draft.base.revision)) {
                        is WriteResult.Failure -> result.notice().also { editor.reject(submission, result.error, requireNotNull(it)) }
                        is WriteResult.Success -> {
                            if (profiles.findSaved(id) != null) editor.acceptSaved(submission, result.value) else editor.forget(id)
                            "已保存，运行配置未改变"
                        }
                    }
                }
                if (currentId() == id) message.value = notice
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                val failure = "无法保存配置，编辑内容已保留"
                editor.reject(submission, WriteError.WriteFailed, failure)
                if (currentId() == id) message.value = failure
            }
            finally { editor.finish(submission) }
        }
    }

    /** 创建动作在没有查看身份时也有效；结果保留到根导航确认消费，避免重建丢失一次性事件。 */
    fun actCreation(action: CreationAction) {
        if (action == CreationAction.Begin) {
            if (mutableCreation.value == null) mutableCreation.value = CreationDraft()
            return
        }
        val current = mutableCreation.value ?: return
        if (action is CreationAction.Acknowledge) {
            if (current.createdId != null && action.token == current.token) mutableCreation.value = null
            return
        }
        if (current.saving || current.createdId != null) return
        when (action) {
            is CreationAction.Edit -> creationInput(current, current.input.edit(action.field, action.raw))
            is CreationAction.Peer -> creationInput(current, current.input.editPeer(action.action))
            is CreationAction.Name -> mutableCreation.value = current.copy(localName = action.raw)
            is CreationAction.Mode -> mutableCreation.value = current.copy(mode = action.mode)
            is CreationAction.Search -> mutableCreation.value = current.copy(search = action.raw)
            CreationAction.Cancel -> mutableCreation.value = null
            CreationAction.Save -> create(current)
        }
    }
    private fun creationInput(current: CreationDraft, input: RawConfigurationInput) {
        mutableCreation.value = current.copy(input = input, validation = ConfigurationInputFactory.validate(input), notice = null)
    }
    private fun create(current: CreationDraft) {
        val validation = ConfigurationInputFactory.validate(current.input)
        val value = validation.value
        if (value == null) { mutableCreation.value = current.copy(validation = validation, notice = "请检查输入错误"); return }
        val token = SaveToken()
        mutableCreation.value = current.copy(validation = validation, token = token, notice = null)
        viewModelScope.launch(writeDispatcher) {
            try {
                when (val document = codec.create(value.core(CoreInstanceId(UUID.randomUUID().toString())))) {
                    is ConfigResult.Failure -> creationFailure(token, "配置解析失败，输入已保留")
                    is ConfigResult.Success -> when (val result = profiles.create(document.value, value.policy, current.localName)) {
                        is WriteResult.Failure -> creationFailure(token, result.notice() ?: "无法保存配置")
                        is WriteResult.Success -> {
                            editor.open(result.value)
                            mutableCreation.value = CreationDraft(token = token, createdId = result.value.profileId)
                        }
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { creationFailure(token, "无法保存配置，输入已保留") }
            finally {
                if (mutableCreation.value?.let { it.token == token && it.createdId == null } == true)
                    mutableCreation.value = mutableCreation.value?.copy(token = null)
            }
        }
    }
    private fun creationFailure(token: SaveToken, notice: String) {
        mutableCreation.value?.takeIf { it.token == token }?.let { mutableCreation.value = it.copy(notice = notice) }
    }
    private fun write(id: LocalProfileId, operation: suspend () -> String?) {
        viewModelScope.launch(writeDispatcher) { val notice = operation(); if (currentId() == id) message.value = notice }
    }
    private fun WriteResult<*>.notice(): String? = when (this) {
        is WriteResult.Success -> null
        is WriteResult.Failure -> when (error) {
            WriteError.Conflict -> "保存版本已变化，请重新载入后再保存"
            WriteError.ValidationError -> "请检查输入错误"
            WriteError.NotFound -> "配置已不存在"
            WriteError.ReadUnavailable -> "尚未成功读取配置，请重试读取"
            WriteError.RevisionExhausted -> "配置修订已达上限"
            WriteError.StoreTooLarge -> "配置存储超过大小上限，未保存"
            WriteError.DocumentTooLarge -> "配置文档超过大小上限，未保存"
            WriteError.KeyUnavailable -> "系统密钥不可用，原始配置已保留，请恢复密钥后重新读取"
            WriteError.WriteFailed -> "无法保存配置，原有配置和编辑内容已保留"
        }
    }
    private fun currentId(): LocalProfileId? {
        val id = viewedProfileId.value
        if (id != activeId) { activeId = id; message.value = null }
        id?.let(profiles::findSaved)?.let(editor::open)
        return id
    }
    private fun report(result: OperationResult) {
        message.value = if (result is OperationResult.Rejected) "操作未执行：${result.reason.userMessage()}" else null
    }
}
