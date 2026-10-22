package com.github.zzwtsy.chuanmen.features.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import kotlinx.coroutines.flow.*

/** 沿用实际会话的日志范围与排列，不按查看配置过滤或生成记录。 */
class LogsViewModel(sessions: StateFlow<SessionState>) : ViewModel() {
    val state = sessions.map(::project).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), project(sessions.value))

    companion object {
        internal fun project(session: SessionState) = LogsUiState(
            session.lifecycle.map { "+${it.elapsedMillis} ms · 会话 ${it.token.sessionId.generation} · 操作 ${it.token.sequence} · 修订 ${it.savedRevision} · ${it.code}" } +
                session.coreDiagnostics?.records.orEmpty().map { "+${it.elapsedMillis} ms · 核心进程 · ${it.level} · ${it.target} · ${it.code}" },
        )
    }
}
