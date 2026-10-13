package com.github.zzwtsy.chuanmen.application

import com.github.zzwtsy.chuanmen.contracts.LocalProfileId
import com.github.zzwtsy.chuanmen.contracts.ProfileRepository
import com.github.zzwtsy.chuanmen.contracts.ProfileSummary
import com.github.zzwtsy.chuanmen.contracts.SessionController
import com.github.zzwtsy.chuanmen.contracts.SessionState
import com.github.zzwtsy.chuanmen.feature.configuration.edit.Draft
import com.github.zzwtsy.chuanmen.feature.configuration.edit.DraftField
import com.github.zzwtsy.chuanmen.feature.configuration.edit.EditorMode

enum class Destination(val title: String) {
    Overview("总览"), Devices("设备"), Configuration("配置"), Logs("日志"),
}

/** 导航的查看对象与运行对象分开；返回与目的地切换不负责丢弃草稿。 */
data class AppNavigation(
    val destination: Destination = Destination.Overview,
    val viewedProfileId: LocalProfileId? = null,
    val settingsOpen: Boolean = false,
)

/** 固定展示样本，没有观测时保留 null；不能参与连接成功判定。 */
data class AppReadings(
    val txBytesPerSecond: List<Double?> = emptyList(),
    val rxBytesPerSecond: List<Double?> = emptyList(),
    val logs: List<String> = emptyList(),
)

/** 外观仅为 P01 内存演示偏好，不提供持久化保证。 */
data class Appearance(val darkTheme: Boolean? = null, val dynamicColor: Boolean = true)

data class AppUiState(
    val profiles: List<ProfileSummary> = emptyList(),
    val navigation: AppNavigation = AppNavigation(),
    val draft: Draft? = null,
    val session: SessionState = SessionState(),
    val readings: AppReadings = AppReadings(),
    val appearance: Appearance = Appearance(),
    val message: String? = null,
) {
    val viewed: ProfileSummary?
        get() = profiles.find { it.saved.profileId == navigation.viewedProfileId }
    val viewingRunningProfile: Boolean
        get() = session.applied?.saved?.profileId == navigation.viewedProfileId && viewed != null

    /** 当前运行网络的已保存内容与实际配置不同时才待应用，修订号变化不代表内容变化。 */
    val pendingApplication: Boolean
        get() {
            if (!viewingRunningProfile) return false
            val saved = viewed?.saved ?: return false
            val applied = session.applied?.saved ?: return false
            return saved.core != applied.core || saved.vpnPolicy != applied.vpnPolicy
        }
}

/** 装配层的依赖，不向 UI 暴露存储实现或引擎实例。 */
data class AppDependencies(val profiles: ProfileRepository, val sessions: SessionController, val readings: AppReadings)

sealed interface AppAction {
    data class Navigate(val destination: Destination) : AppAction
    data class ViewProfile(val profileId: LocalProfileId) : AppAction
    data class Edit(val field: DraftField, val raw: String) : AppAction
    data class ChangeEditorMode(val mode: EditorMode) : AppAction
    data class Rename(val name: String) : AppAction
    data class SetAppearance(val appearance: Appearance) : AppAction
    data object Save : AppAction
    data object Apply : AppAction
    data object Discard : AppAction
    data object RestoreApplied : AppAction
    data object Connect : AppAction
    data object Disconnect : AppAction
    data object OpenSettings : AppAction
    data object Back : AppAction
}
