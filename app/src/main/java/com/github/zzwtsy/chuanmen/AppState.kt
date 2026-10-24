package com.github.zzwtsy.chuanmen

import com.github.zzwtsy.chuanmen.lib.Appearance
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.vpn.DataFreshness

enum class Destination(val title: String) {
    Overview("总览"), Devices("设备"), Configuration("配置"), Logs("日志"),
}

/** 导航与查看身份独立于真实运行对象；切换不保存、不连接或丢弃草稿。 */
data class AppNavigation(
    val destination: Destination = Destination.Overview,
    val viewedProfileId: LocalProfileId? = null,
    val settingsOpen: Boolean = false,
    val creating: Boolean = false,
)

data class ProfileChoice(val profileId: LocalProfileId, val displayName: String)
data class RunningProfileSummary(val profileId: LocalProfileId, val displayName: String, val freshness: DataFreshness)

/** 根界面仅接收选择项与顶栏摘要，功能投影由各功能状态所有者维护。 */
data class AppUiState(
    val profiles: List<ProfileChoice> = emptyList(),
    val navigation: AppNavigation = AppNavigation(),
    val appearance: Appearance = Appearance(),
    val running: RunningProfileSummary? = null,
) {
    val viewed: ProfileChoice? get() = profiles.find { it.profileId == navigation.viewedProfileId }
}

sealed interface AppAction {
    data class Navigate(val destination: Destination) : AppAction
    data class ViewProfile(val profileId: LocalProfileId) : AppAction
    data object CreateProfile : AppAction
    data object CancelCreation : AppAction
    data class FinishCreation(val profileId: LocalProfileId) : AppAction
    data object OpenSettings : AppAction
    data object EditPolicy : AppAction
    data object Back : AppAction
}
