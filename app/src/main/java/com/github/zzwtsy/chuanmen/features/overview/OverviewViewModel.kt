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
            val applied = session.applied?.takeIf { exists && it.saved.profileId == viewedId }
            val ownsOperation = exists && session.requestedProfileId == viewedId
            val current = applied != null && applied.freshness == DataFreshness.Fresh &&
                applied.sessionId == session.sessionId && session.phase != ConnectionPhase.OutcomeUnknown
            val core = session.core?.value?.takeIf {
                current && session.core.freshness == DataFreshness.Fresh && it.sessionId == applied?.sessionId
            }
            val tx = if (current) session.traffic.takeLast(60).map { it.txBytesPerSecond } else emptyList()
            val rx = if (current) session.traffic.takeLast(60).map { it.rxBytesPerSecond } else emptyList()
            val disconnect = (ownsOperation && session.phase !in listOf(ConnectionPhase.Idle, ConnectionPhase.Failed)) || current
            return OverviewUiState(
                status = if (ownsOperation || current) session.phase.label() else "未连接",
                ip = applied?.virtualIpv4?.value,
                historicalAddress = applied != null && !current,
                onlineDevices = if (applied == null) "暂无数据" else core?.peers?.count { it.connections > 0 }?.toString() ?: "未采样",
                waitingForPeers = core?.peers?.none { it.connections > 0 } == true,
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
            )
        }
    }
}

private fun rate(value: Double?) = value?.let { "${it.toLong()} B/s" } ?: "暂无数据"
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
