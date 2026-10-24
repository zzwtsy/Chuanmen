package com.github.zzwtsy.chuanmen.features.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.ProfileRepository
import com.github.zzwtsy.chuanmen.lib.vpn.ConnectionPhase
import com.github.zzwtsy.chuanmen.lib.vpn.DataFreshness
import com.github.zzwtsy.chuanmen.lib.vpn.OperationResult
import com.github.zzwtsy.chuanmen.lib.vpn.SessionController
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import com.github.zzwtsy.chuanmen.lib.vpn.userMessage
import com.github.zzwtsy.chuanmen.lib.vpn.SessionObservation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** 总览命令交给唯一会话客户端；投影不采样，也不以流量推断连接成功。 */
class OverviewViewModel(
    private val profiles: ProfileRepository,
    private val sessions: SessionController,
    private val viewedProfileId: StateFlow<LocalProfileId?>,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)
    private var activeId = viewedProfileId.value
    init { viewModelScope.launch { viewedProfileId.collect { currentId() } } }

    val state = combine(profiles.profiles, sessions.state, viewedProfileId, message) { saved, session, id, notice ->
        project(session, id, saved.any { it.saved.profileId == id }, notice.takeIf { id == activeId })
            .copy(profileName = saved.firstOrNull { it.saved.profileId == id }?.displayName)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), project(
        sessions.state.value, viewedProfileId.value, profiles.profiles.value.any { it.saved.profileId == viewedProfileId.value },
    ))

    fun act(action: OverviewAction) {
        val id = currentId()
        val result = when (action) {
            OverviewAction.Connect -> id?.let(profiles::findSaved)?.let(sessions::connect)
            OverviewAction.Disconnect -> sessions.disconnect()
            OverviewAction.RestoreRunning -> sessions.restoreRunning()
            OverviewAction.ViewDevices -> null
        }
        if (action != OverviewAction.ViewDevices) message.value =
            if (result is OperationResult.Rejected) "操作未执行：${result.reason.userMessage()}" else null
    }

    private fun currentId(): LocalProfileId? {
        val id = viewedProfileId.value
        if (activeId != id) { activeId = id; message.value = null }
        return id
    }

    companion object {
        internal fun project(session: SessionState, viewedId: LocalProfileId?, exists: Boolean, message: String? = null): OverviewUiState {
            val observation = SessionObservation.from(session, viewedId.takeIf { exists })
            val applied = observation.applied
            val ownsOperation = exists && session.requestedProfileId == viewedId
            val current = observation.confirmed
            val index = observation.index
            val tx = if (current) session.traffic.takeLast(60).map { it.txBytesPerSecond } else emptyList()
            val rx = if (current) session.traffic.takeLast(60).map { it.rxBytesPerSecond } else emptyList()
            val disconnect = (ownsOperation && session.phase !in listOf(ConnectionPhase.Idle, ConnectionPhase.Failed)) || current
            return OverviewUiState(
                status = if (ownsOperation || current) session.phase.label() else "未连接",
                ip = applied?.virtualIpv4?.value,
                historicalAddress = applied?.virtualIpv4?.value != null && !observation.addressIsCurrent,
                onlineDevices = if (applied == null) "暂无数据" else index?.let {
                    if (it.unknown.isEmpty()) it.members.size.toString() else "${it.members.size}（已确认）"
                } ?: "未采样",
                waitingForPeers = index?.members?.isEmpty() == true,
                showDevices = applied != null,
                showDisconnect = disconnect,
                disconnectLabel = if (session.phase in listOf(ConnectionPhase.Preparing, ConnectionPhase.AwaitingPermission,
                    ConnectionPhase.Connecting, ConnectionPhase.AwaitingAddress, ConnectionPhase.PreparingTun)) "取消连接" else "断开",
                disconnectEnabled = session.phase != ConnectionPhase.Stopping,
                connectEnabled = exists && session.pending == null && session.phase !in setOf(ConnectionPhase.Stopping, ConnectionPhase.OutcomeUnknown),
                // 恢复对象来自已确认的运行历史，不随最后一次失败请求的配置归属改变。
                canRestore = applied != null && !current && session.phase == ConnectionPhase.Failed,
                failure = session.failure?.takeIf { ownsOperation }?.userMessage(), message = message,
                tx = tx, rx = rx, upload = rate(tx.lastOrNull()), download = rate(rx.lastOrNull()),
                pathSummary = index?.let { snapshot ->
                    val paths = snapshot.members.map { id ->
                        snapshot.routes[id]?.nextHopPeerId?.value?.let { hop ->
                            if (hop == id) "直连" else "中继"
                        } ?: "未知"
                    }
                    if (paths.isEmpty()) "路径未知" else "直连 ${paths.count { it == "直连" }} · 中继 ${paths.count { it == "中继" }} · 未知 ${paths.count { it == "未知" }}"
                } ?: "路径未知",
                points = if (current) session.traffic else emptyList(),
                upperBound = session.trafficUpperBound,
            )
        }
    }
}

internal fun rate(value: Double?): String {
    if (value == null || !value.isFinite() || value < 0) return "暂无数据"
    return when {
        value >= 1_048_576 -> String.format(java.util.Locale.ROOT, "%.1f MiB/s", value / 1_048_576)
        value >= 1_024 -> String.format(java.util.Locale.ROOT, "%.1f KiB/s", value / 1_024)
        else -> "${value.toLong()} B/s"
    }
}
private fun ConnectionPhase.label() = when (this) {
    ConnectionPhase.Idle -> "未连接"
    ConnectionPhase.Preparing -> "准备中"
    ConnectionPhase.AwaitingPermission -> "等待 VPN 授权"
    ConnectionPhase.Connecting -> "连接中"
    ConnectionPhase.AwaitingAddress -> "等待核心地址"
    ConnectionPhase.PreparingTun -> "准备 VPN 接口"
    ConnectionPhase.Running -> "已连接"
    ConnectionPhase.Stopping -> "停止中"
    ConnectionPhase.Reconnecting -> "重新连接中"
    ConnectionPhase.OutcomeUnknown -> "结果未确认"
    ConnectionPhase.Failed -> "连接失败"
}
