package com.github.zzwtsy.chuanmen.features.configuration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.zzwtsy.chuanmen.features.configuration.lib.ConfigurationEditor
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.ProfileRepository
import com.github.zzwtsy.chuanmen.lib.vpn.OperationResult
import com.github.zzwtsy.chuanmen.lib.vpn.SessionController
import com.github.zzwtsy.chuanmen.lib.vpn.userMessage
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** 唯一草稿所有者；按处理动作时的查看身份编辑，页面订阅不拥有真实会话。 */
class ConfigurationViewModel(
    private val profiles: ProfileRepository,
    private val sessions: SessionController,
    private val viewedProfileId: StateFlow<LocalProfileId?>,
) : ViewModel() {
    private val editor = ConfigurationEditor(profiles)
    private val message = MutableStateFlow<String?>(null)
    private var activeId = viewedProfileId.value

    init {
        activeId?.let(editor::open)
        viewModelScope.launch { viewedProfileId.collect { currentId() } }
    }

    val state = combine(profiles.profiles, sessions.state, viewedProfileId, editor.drafts, message) {
        saved, session, id, drafts, notice ->
        ConfigurationUiState.project(saved, session, id, drafts[id], notice.takeIf { id == activeId })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConfigurationUiState.project(
        profiles.profiles.value, sessions.state.value, activeId, editor.drafts.value[activeId],
    ))

    fun act(action: ConfigurationAction) {
        val id = currentId() ?: return
        when (action) {
            is ConfigurationAction.Edit -> editor.edit(id, action.field, action.raw)
            is ConfigurationAction.ChangeMode -> editor.mode(id, action.mode)
            is ConfigurationAction.Rename -> profiles.rename(id, action.name)
            ConfigurationAction.Save -> message.value =
                if (editor.save(id) == null) "请检查输入错误" else "已保存，运行配置未改变"
            ConfigurationAction.Apply -> profiles.findSaved(id)?.let { report(sessions.apply(it)) }
            ConfigurationAction.Discard -> editor.discard(id)
            ConfigurationAction.Restore -> sessions.state.value.applied?.saved?.takeIf { it.profileId == id }?.let(editor::restore)
        }
    }

    private fun currentId(): LocalProfileId? {
        val id = viewedProfileId.value
        if (id != activeId) { activeId = id; message.value = null }
        id?.let(editor::open)
        return id
    }

    private fun report(result: OperationResult) {
        message.value = if (result is OperationResult.Rejected) "操作未执行：${result.reason.userMessage()}" else null
    }
}
