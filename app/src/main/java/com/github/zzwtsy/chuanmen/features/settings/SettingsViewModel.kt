package com.github.zzwtsy.chuanmen.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.zzwtsy.chuanmen.lib.Appearance
import com.github.zzwtsy.chuanmen.lib.AppearancePreferences
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.ProfileRepository
import com.github.zzwtsy.chuanmen.lib.profile.ProfileSummary
import kotlinx.coroutines.flow.*

/** 设置只有外观写入口；查看配置的互联网范围由仓库只读投影。 */
class SettingsViewModel(
    profiles: ProfileRepository, viewedProfileId: StateFlow<LocalProfileId?>,
    private val preferences: AppearancePreferences,
) : ViewModel() {
    val state = combine(profiles.profiles, viewedProfileId, preferences.state, ::project).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), project(profiles.profiles.value, viewedProfileId.value, preferences.state.value),
    )
    fun setAppearance(appearance: Appearance) { preferences.update(appearance) }

    companion object {
        internal fun project(profiles: List<ProfileSummary>, viewedId: LocalProfileId?, appearance: Appearance) = SettingsUiState(
            appearance, when (profiles.find { it.saved.profileId == viewedId }?.saved?.vpnPolicy?.includeInternet) {
                true -> "互联网流量：已开启"
                false -> "互联网流量：未开启"
                null -> "暂无网络策略"
            },
        )
    }
}
