package com.github.zzwtsy.chuanmen.feature.devices

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.github.zzwtsy.chuanmen.contracts.AppliedSessionSnapshot
import com.github.zzwtsy.chuanmen.contracts.DataFreshness
import com.github.zzwtsy.chuanmen.contracts.CoreSnapshot
import com.github.zzwtsy.chuanmen.contracts.CoreBuildInfo
import com.github.zzwtsy.chuanmen.contracts.Observation
import com.github.zzwtsy.chuanmen.shared.designsystem.ConfigurationGroup
import com.github.zzwtsy.chuanmen.shared.designsystem.CopyIpButton
import com.github.zzwtsy.chuanmen.shared.designsystem.DeviceStatistic

@Composable
fun DevicesScreen(applied: AppliedSessionSnapshot?, simulated: Boolean = false,
    core: Observation<CoreSnapshot>? = null, buildInfo: Observation<CoreBuildInfo>? = null) {
    Text("设备", style = MaterialTheme.typography.headlineSmall)
    if (applied == null) {
        Text("当前查看网络未连接")
        return
    }
    if (applied.freshness == DataFreshness.Stale) {
        Text("当前查看网络运行结果未确认")
        return
    }
    val actual = core?.value?.takeIf { core.freshness == DataFreshness.Fresh && it.sessionId == applied.sessionId }
    ConfigurationGroup(if (simulated) "本机 · 模拟数据" else "本机") {
        Text(applied.saved.core.hostname ?: "名称未报告")
        applied.virtualIpv4.value?.let { Text(it); CopyIpButton(it) }
        if (actual == null) DeviceStatistic("NAT", "未知 / 尚未探测") else {
            DeviceStatistic("UDP NAT", natType(actual.udpNatType))
            DeviceStatistic("TCP NAT", natType(actual.tcpNatType))
        }
        DeviceStatistic("核心版本", buildInfo?.value?.coreVersion ?: "未报告")
        DeviceStatistic("路径", "未知路径")
        DeviceStatistic("延迟", "未测得")
    }
    actual?.peers?.filter { it.connections > 0 }?.forEach { peer ->
        val route = actual.routes.firstOrNull { it.peerId == peer.peerId }
        ConfigurationGroup("设备 ${peer.peerId}") {
            route?.ipv4?.address?.let { Text(it); CopyIpButton(it) }
            DeviceStatistic("连接", peer.connections.toString())
            DeviceStatistic("核心版本", route?.version?.takeIf { it.isNotBlank() } ?: "未报告")
            DeviceStatistic("路由代价", route?.cost?.toString() ?: "未报告")
            route?.proxyCidrs?.forEach { Text("路由 ${it.address}/${it.prefixLength}") }
        }
    }
    if (actual == null) Text("其他设备暂无采样")
    else if (actual.peers.none { it.connections > 0 }) Text("等待其他设备加入")
}

/** 固定核心 common.proto 的枚举；未来值保留未知编号，不猜测测量结果。 */
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
