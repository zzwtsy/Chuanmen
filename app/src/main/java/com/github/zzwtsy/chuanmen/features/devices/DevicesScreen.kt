package com.github.zzwtsy.chuanmen.features.devices

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.zzwtsy.chuanmen.components.CopyIpButton
import com.github.zzwtsy.chuanmen.components.DeviceStatistic
import com.github.zzwtsy.chuanmen.components.SectionCard
import com.github.zzwtsy.chuanmen.features.devices.components.DeviceCard
import com.github.zzwtsy.chuanmen.lib.vpn.ProbeStatus

@Composable
internal fun DevicesRoute(model: DevicesViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    DisposableEffect(model) { onDispose { model.act(DeviceAction.CloseDetails) } }
    DevicesScreen(state, model::act)
}

@Composable
fun DevicesScreen(state: DevicesUiState, onAction: (DeviceAction) -> Unit = {}) {
    Text("设备", style = MaterialTheme.typography.headlineSmall)
    state.notice?.let { Text(it) }
    state.local?.let { local ->
        SectionCard("本机") {
            Text(local.name)
            local.ip?.let { Text(if (local.historicalAddress) "上次地址：$it" else it); CopyIpButton(it, enabled = !local.historicalAddress) }
            if (local.udpNat == null) DeviceStatistic("NAT", "未知 / 尚未探测") else {
                DeviceStatistic("UDP NAT", local.udpNat)
                DeviceStatistic("TCP NAT", local.tcpNat ?: "未知 / 尚未探测")
            }
            DeviceStatistic("核心版本", local.version)
            DeviceStatistic("路径", "未知路径")
            DeviceStatistic("延迟", "未测得")
        }
    }
    state.peers.forEach { peer -> key(peer.identity) { DeviceCard(peer, peer.identity in state.expanded, onAction) } }
    state.peersNotice?.let { Text(it) }
    if (state.relays.isNotEmpty()) {
        Text("中继连接", style = MaterialTheme.typography.titleMedium)
        Text("公共中继不计入同网在线设备数")
        state.relays.forEach { relay -> key(relay.identity) { DeviceCard(relay, relay.identity in state.expanded, onAction) } }
    }
    state.detail?.let { detail ->
        AlertDialog(
            onDismissRequest = { onAction(DeviceAction.CloseDetails) },
            title = { Text(detail.title) },
            confirmButton = { TextButton(onClick = { onAction(DeviceAction.CloseDetails) }) { Text("返回列表") } },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    detail.unavailable?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text(detail.path)
                    detail.ip?.let { Text(if (detail.online) it else "上次地址：$it") }
                    CopyIpButton(detail.ip.orEmpty(), enabled = detail.online && detail.ip != null)
                    DeviceStatistic("UDP NAT", detail.udpNat)
                    DeviceStatistic("TCP NAT", detail.tcpNat)
                    DeviceStatistic("核心版本", detail.version)
                    DeviceStatistic("邻居连接", detail.connections)
                    detail.statistics.forEach { Text(it) }
                    detail.routes.forEach { Text(it) }
                    TextButton(onClick = { onAction(DeviceAction.StartProbe) },
                        enabled = detail.probeTarget != null && state.probe?.status != ProbeStatus.Running) { Text("探测连接") }
                    if (state.probe?.status == ProbeStatus.Running) {
                        TextButton(onClick = { onAction(DeviceAction.CancelProbe) }) { Text("取消探测") }
                    }
                    state.probe?.let { probe ->
                        Text(if (state.probeHistorical) "上次目标：${probe.target.ipv4}（历史结果）" else "探测目标：${probe.target.ipv4}")
                        Text(when (probe.status) {
                            ProbeStatus.Running -> "正在探测"
                            ProbeStatus.Complete -> "本次探测完成"
                            ProbeStatus.Cancelled -> "探测已取消（部分结果）"
                            ProbeStatus.Unavailable -> "探测不可用：${probe.unavailable.probeMessage()}"
                        })
                        Text("发送 ${probe.sent} · 回复 ${probe.received}")
                        Text("本次探测丢包：${probe.loss?.let { "${(it * 100).toInt()}%" } ?: "未知"}")
                        probe.rttMillis.forEachIndexed { index, rtt -> Text("回复 ${index + 1} RTT：$rtt ms") }
                    } ?: Text("端到端丢包：尚未探测")
                    state.probeNotice?.let { Text(it) }
                }
            },
        )
    }
}

private fun com.github.zzwtsy.chuanmen.lib.vpn.ProbeUnavailable?.probeMessage() = when (this) {
    com.github.zzwtsy.chuanmen.lib.vpn.ProbeUnavailable.NoFreshSession -> "没有当前有效的设备和会话数据"
    com.github.zzwtsy.chuanmen.lib.vpn.ProbeUnavailable.RouteUnavailable -> "目标路由不属于当前 VPN"
    com.github.zzwtsy.chuanmen.lib.vpn.ProbeUnavailable.PermissionOrUnsupported -> "系统不支持或未授予 ICMP socket 权限"
    com.github.zzwtsy.chuanmen.lib.vpn.ProbeUnavailable.SendFailed -> "请求发送失败"
    else -> "接收失败"
}
