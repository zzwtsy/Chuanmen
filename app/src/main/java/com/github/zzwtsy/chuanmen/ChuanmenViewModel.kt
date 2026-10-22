package com.github.zzwtsy.chuanmen

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.zzwtsy.chuanmen.lib.AppearancePreferences
import com.github.zzwtsy.chuanmen.lib.profile.ProfileSummary
import com.github.zzwtsy.chuanmen.lib.vpn.ConnectionPhase
import com.github.zzwtsy.chuanmen.lib.vpn.DataFreshness
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import kotlinx.coroutines.flow.*

/** Activity 导航与外壳所有者；只读配置和会话，不提交功能命令。 */
class ChuanmenViewModel(
    private val profiles: StateFlow<List<ProfileSummary>>,
    sessions: StateFlow<SessionState>,
) : ViewModel() {
    private val destination = MutableStateFlow(Destination.Overview)
    private val settingsOpen = MutableStateFlow(false)
    private val mutableViewedProfileId = MutableStateFlow(profiles.value.firstOrNull()?.saved?.profileId)
    val viewedProfileId = mutableViewedProfileId.asStateFlow()
    val appearancePreferences = AppearancePreferences()

    val state = combine(profiles, sessions, destination, viewedProfileId, settingsOpen) { saved, session, page, id, settings ->
        project(saved, session, AppNavigation(page, id, settings))
    }.combine(appearancePreferences.state) { ui, appearance -> ui.copy(appearance = appearance) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), project(
            profiles.value, sessions.value, AppNavigation(viewedProfileId = viewedProfileId.value),
        ).copy(appearance = appearancePreferences.state.value))

    fun act(action: AppAction) {
        when (action) {
            is AppAction.Navigate -> { destination.value = action.destination; settingsOpen.value = false }
            is AppAction.ViewProfile -> if (profiles.value.any { it.saved.profileId == action.profileId }) mutableViewedProfileId.value = action.profileId
            AppAction.OpenSettings -> settingsOpen.value = true
            AppAction.Back -> if (settingsOpen.value) settingsOpen.value = false else destination.value = Destination.Overview
        }
    }

    private fun project(profiles: List<ProfileSummary>, session: SessionState, navigation: AppNavigation): AppUiState {
        val running = session.applied?.let { applied ->
            val current = applied.sessionId == session.sessionId && session.phase != ConnectionPhase.OutcomeUnknown
            RunningProfileSummary(applied.saved.profileId,
                profiles.find { it.saved.profileId == applied.saved.profileId }?.displayName ?: "未知配置",
                if (current) applied.freshness else DataFreshness.Stale)
        }
        return AppUiState(profiles.map { ProfileChoice(it.saved.profileId, it.displayName) }, navigation, running = running)
    }
}
