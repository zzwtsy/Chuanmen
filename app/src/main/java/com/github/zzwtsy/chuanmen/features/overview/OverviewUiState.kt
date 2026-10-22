package com.github.zzwtsy.chuanmen.features.overview

/** 总览所需的展示值；未知速率保留 null，是否连接来自真实会话投影。 */
data class OverviewUiState(
    val status: String = "未连接",
    val ip: String? = null,
    val historicalAddress: Boolean = false,
    val onlineDevices: String = "暂无数据",
    val waitingForPeers: Boolean = false,
    val showDevices: Boolean = false,
    val showDisconnect: Boolean = false,
    val disconnectLabel: String = "断开",
    val disconnectEnabled: Boolean = false,
    val connectEnabled: Boolean = false,
    val canRestore: Boolean = false,
    val failure: String? = null,
    val message: String? = null,
    val tx: List<Double?> = emptyList(),
    val rx: List<Double?> = emptyList(),
    val upload: String = "暂无数据",
    val download: String = "暂无数据",
)
