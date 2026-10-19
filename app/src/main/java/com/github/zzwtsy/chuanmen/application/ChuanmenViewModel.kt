package com.github.zzwtsy.chuanmen.application

import com.github.zzwtsy.chuanmen.shared.presentation.userMessage
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.zzwtsy.chuanmen.contracts.OperationResult
import com.github.zzwtsy.chuanmen.feature.configuration.edit.ConfigurationEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * 持有编辑与导航状态，通过显式动作调用领域接口。
 *
 * [assemble] 由 flavor 装配；作用域在 ViewModel 清理时取消，重组不重新创建引擎。
 * 草稿只在本 ViewModel 生命周期保留，不提供磁盘持久化或进程恢复保证。
 */
class ChuanmenViewModel(assemble: (CoroutineScope) -> AppDependencies) : ViewModel() {
    private val dependencies = assemble(viewModelScope)
    private val editor = ConfigurationEditor(dependencies.profiles)
    private val navigation = MutableStateFlow(
        AppNavigation(viewedProfileId = dependencies.profiles.profiles.value.firstOrNull()?.saved?.profileId),
    )
    private val appearance = MutableStateFlow(Appearance())
    private val message = MutableStateFlow<String?>(null)

    init {
        navigation.value.viewedProfileId?.let(editor::open)
    }

    val state = combine(
        dependencies.profiles.profiles, dependencies.sessions.state, navigation, editor.drafts,
    ) { profiles, session, route, drafts ->
        val readings = if (dependencies.readings.simulated) dependencies.readings else AppReadings(
            session.traffic.map { it.txBytesPerSecond }, session.traffic.map { it.rxBytesPerSecond },
            session.lifecycle.map { "+${it.elapsedMillis} ms · 会话 ${it.token.sessionId.generation} · 操作 ${it.token.sequence} · 修订 ${it.savedRevision} · ${it.code}" } +
                session.coreDiagnostics?.records.orEmpty().map { "+${it.elapsedMillis} ms · 核心进程 · ${it.level} · ${it.target} · ${it.code}" },
        )
        AppUiState(profiles, route, drafts[route.viewedProfileId], session, readings)
    }.combine(appearance) { ui, preferences -> ui.copy(appearance = preferences) }
        .combine(message) { ui, notice -> ui.copy(message = notice) }
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5000),
            AppUiState(
                dependencies.profiles.profiles.value, navigation.value,
                editor.drafts.value[navigation.value.viewedProfileId], dependencies.sessions.state.value,
                dependencies.readings,
            ),
        )

    fun act(action: AppAction) {
        val id = navigation.value.viewedProfileId
        when (action) {
            is AppAction.Navigate -> navigation.value = navigation.value.copy(destination = action.destination, settingsOpen = false)
            is AppAction.ViewProfile -> {
                if (dependencies.profiles.findSaved(action.profileId) == null) return
                editor.open(action.profileId)
                navigation.value = navigation.value.copy(viewedProfileId = action.profileId)
                message.value = null
            }
            is AppAction.Edit -> id?.let { editor.edit(it, action.field, action.raw) }
            is AppAction.ChangeEditorMode -> id?.let { editor.mode(it, action.mode) }
            is AppAction.Rename -> id?.let { dependencies.profiles.rename(it, action.name) }
            is AppAction.SetAppearance -> appearance.value = action.appearance
            AppAction.Save -> id?.let {
                message.value = if (editor.save(it) == null) "请检查输入错误" else "已保存，运行配置未改变"
            }
            AppAction.Apply -> id?.let { dependencies.profiles.findSaved(it) }?.let { report(dependencies.sessions.apply(it)) }
            AppAction.Discard -> id?.let(editor::discard)
            AppAction.RestoreApplied -> dependencies.sessions.state.value.applied?.saved?.let {
                if (it.profileId == id) editor.restore(it)
            }
            AppAction.RestoreRunning -> report(dependencies.sessions.restoreRunning())
            AppAction.Connect -> id?.let { dependencies.profiles.findSaved(it) }?.let { report(dependencies.sessions.connect(it)) }
            AppAction.Disconnect -> report(dependencies.sessions.disconnect())
            AppAction.OpenSettings -> navigation.value = navigation.value.copy(settingsOpen = true)
            AppAction.Back -> navigation.value = if (navigation.value.settingsOpen) {
                navigation.value.copy(settingsOpen = false)
            } else {
                navigation.value.copy(destination = Destination.Overview)
            }
        }
    }

    private fun report(result: OperationResult) {
        message.value = when (result) {
            is OperationResult.Rejected -> "操作未执行：${result.reason.userMessage()}"
            else -> null
        }
    }
}
