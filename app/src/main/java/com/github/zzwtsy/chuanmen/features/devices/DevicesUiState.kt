package com.github.zzwtsy.chuanmen.features.devices

/** 展示值已经过归属和新鲜度核对；没有观测时不生成设备卡片。 */
data class DevicesUiState(
    val notice: String? = "当前查看网络未连接",
    val local: LocalDeviceUiState? = null,
    val peers: List<DeviceCardUiState> = emptyList(),
    val peersNotice: String? = null,
)

data class LocalDeviceUiState(
    val name: String, val ip: String?, val udpNat: String?, val tcpNat: String?, val version: String,
)

data class DeviceCardUiState(
    val title: String, val ip: String?, val connections: String,
    val version: String, val routeCost: String, val routes: List<String>,
)
