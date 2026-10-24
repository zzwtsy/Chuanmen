package com.github.zzwtsy.chuanmen.features.devices

import com.github.zzwtsy.chuanmen.lib.vpn.SessionId

/** 展示值已经过归属和新鲜度核对；没有观测时不生成设备卡片。 */
data class DevicesUiState(
    val notice: String? = "当前查看网络未连接",
    val local: LocalDeviceUiState? = null,
    val peers: List<DeviceCardUiState> = emptyList(),
    val peersNotice: String? = null,
    val detail: DeviceCardUiState? = null,
    val expanded: Set<DeviceIdentity> = emptySet(),
    val probe: com.github.zzwtsy.chuanmen.lib.vpn.ProbeResult? = null,
    val probeNotice: String? = null,
    val relays: List<DeviceCardUiState> = emptyList(),
    val probeHistorical: Boolean = false,
)

data class LocalDeviceUiState(
    val name: String, val ip: String?, val udpNat: String?, val tcpNat: String?, val version: String,
    val historicalAddress: Boolean = false,
)

data class DeviceCardUiState(
    val title: String, val ip: String?, val connections: String,
    val version: String, val routeCost: String, val routes: List<String>,
    val identity: DeviceIdentity? = null,
    val path: String = "未知路径",
    val udpNat: String = "不支持",
    val tcpNat: String = "不支持",
    val statistics: List<String> = emptyList(),
    val online: Boolean = true,
    val unavailable: String? = null,
    val probeTarget: com.github.zzwtsy.chuanmen.lib.vpn.ProbeTarget? = null,
    /** 完整观测身份用于结果归属；发起探测还须通过当前平台资格检查。 */
    val observedTarget: com.github.zzwtsy.chuanmen.lib.vpn.ProbeTarget? = probeTarget,
)

/** 列表重排和地址变化不改变身份；会话切换使身份整体失效。 */
data class DeviceIdentity(val sessionId: SessionId, val peerId: Long)

sealed interface DeviceAction {
    data class Expand(val identity: DeviceIdentity) : DeviceAction
    data class Details(val identity: DeviceIdentity) : DeviceAction
    data object CloseDetails : DeviceAction
    data object StartProbe : DeviceAction
    data object CancelProbe : DeviceAction
}
