package com.github.zzwtsy.chuanmen.features.overview

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.zzwtsy.chuanmen.components.ConditionalActionArea
import com.github.zzwtsy.chuanmen.components.CopyIpButton
import com.github.zzwtsy.chuanmen.components.DeviceStatistic
import com.github.zzwtsy.chuanmen.components.SectionCard
import com.github.zzwtsy.chuanmen.features.overview.components.TrafficTrend

enum class OverviewAction { Connect, Disconnect, RestoreRunning, ViewDevices }

/** 页面只收集展示状态；查看设备直接交给根导航回调。 */
@Composable
internal fun OverviewRoute(model: OverviewViewModel, onViewDevices: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    OverviewScreen(state) { action ->
        if (action == OverviewAction.ViewDevices) onViewDevices() else model.act(action)
    }
}

@Composable
fun OverviewScreen(state: OverviewUiState, onAction: (OverviewAction) -> Unit) {
    SectionCard("虚拟网络") {
        state.profileName?.let { Text("当前查看：$it") }
        Text(state.status)
        state.ip?.let { ip ->
            if (state.historicalAddress) Text("上次确认的地址（历史）")
            Text(ip, style = MaterialTheme.typography.titleMedium)
            CopyIpButton(ip, enabled = !state.historicalAddress)
        }
        DeviceStatistic("在线设备", state.onlineDevices)
        Text(state.pathSummary)
        if (state.waitingForPeers) Text("等待其他设备加入")
        if (state.showDevices) Button(onClick = { onAction(OverviewAction.ViewDevices) }) { Text("查看设备") }
        if (state.showDisconnect) Button(onClick = { onAction(OverviewAction.Disconnect) }, enabled = state.disconnectEnabled) { Text(state.disconnectLabel) }
        else Button(onClick = { onAction(OverviewAction.Connect) }, enabled = state.connectEnabled) { Text("连接") }
        if (state.canRestore) Button(onClick = { onAction(OverviewAction.RestoreRunning) }) { Text("恢复上次运行内容") }
        ConditionalActionArea(state.failure != null) { state.failure?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
    }
    SectionCard("实时流量") {
        DeviceStatistic("上传", state.upload)
        DeviceStatistic("下载", state.download)
        Text("最近 60 秒")
        TrafficTrend(state.points, state.upperBound, "上传 ${state.upload}，下载 ${state.download}")
    }
    state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}
