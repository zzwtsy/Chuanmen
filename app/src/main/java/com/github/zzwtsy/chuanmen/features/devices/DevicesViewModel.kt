package com.github.zzwtsy.chuanmen.features.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.vpn.ConnectionPhase
import com.github.zzwtsy.chuanmen.lib.vpn.DataFreshness
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import kotlinx.coroutines.flow.*

/** 匹配查看网络、实际会话代次和观测；页面不拥有会话或采样任务。 */
class DevicesViewModel(sessions: StateFlow<SessionState>, viewedProfileId: StateFlow<LocalProfileId?>) : ViewModel() {
    val state = combine(sessions, viewedProfileId, ::project).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), project(sessions.value, viewedProfileId.value),
    )

    companion object {
        internal fun project(session: SessionState, viewedId: LocalProfileId?): DevicesUiState {
            val applied = session.applied?.takeIf { it.saved.profileId == viewedId } ?: return DevicesUiState()
            if (applied.freshness != DataFreshness.Fresh || applied.sessionId != session.sessionId || session.phase == ConnectionPhase.OutcomeUnknown) {
                return DevicesUiState(notice = "当前查看网络运行结果未确认")
            }
            val actual = session.core?.value?.takeIf { session.core.freshness == DataFreshness.Fresh && it.sessionId == applied.sessionId }
            val local = LocalDeviceUiState(
                applied.saved.core.hostname ?: "名称未报告", applied.virtualIpv4.value,
                actual?.let { natType(it.udpNatType) }, actual?.let { natType(it.tcpNatType) },
                session.buildInfo?.takeIf { it.freshness == DataFreshness.Fresh }?.value?.coreVersion ?: "未报告",
            )
            val peers = actual?.peers.orEmpty().filter { it.connections > 0 }.map { peer ->
                val route = actual?.routes?.firstOrNull { it.peerId == peer.peerId }
                DeviceCardUiState("设备 ${peer.peerId}", route?.ipv4?.address, peer.connections.toString(),
                    route?.version?.takeIf(String::isNotBlank) ?: "未报告", route?.cost?.toString() ?: "未报告",
                    route?.proxyCidrs.orEmpty().map { "路由 ${it.address}/${it.prefixLength}" })
            }
            return DevicesUiState(null, local, peers,
                if (actual == null) "其他设备暂无采样" else if (peers.isEmpty()) "等待其他设备加入" else null)
        }
    }
}

/** 固定核心 common.proto 的枚举；未知编号原样保留，不猜测测量结果。 */
private fun natType(value: Int?): String = when (value) {
    null, 0 -> "未知 / 尚未探测"
    1 -> "OpenInternet"
    2 -> "NoPAT"
    3 -> "FullCone"
    4 -> "Restricted"
    5 -> "PortRestricted"
    6 -> "Symmetric"
    7 -> "SymUdpFirewall"
    8 -> "SymmetricEasyInc"
    9 -> "SymmetricEasyDec"
    else -> "未知类型 ($value)"
}
