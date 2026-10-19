package com.github.zzwtsy.chuanmen.feature.overview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.zzwtsy.chuanmen.contracts.ConnectionPhase
import com.github.zzwtsy.chuanmen.contracts.DataFreshness
import com.github.zzwtsy.chuanmen.contracts.LocalProfileId
import com.github.zzwtsy.chuanmen.contracts.SessionState
import com.github.zzwtsy.chuanmen.shared.designsystem.ConditionalActionArea
import com.github.zzwtsy.chuanmen.shared.designsystem.ConfigurationGroup
import com.github.zzwtsy.chuanmen.shared.designsystem.CopyIpButton
import com.github.zzwtsy.chuanmen.shared.designsystem.DeviceStatistic
import com.github.zzwtsy.chuanmen.shared.presentation.userMessage

data class OverviewUiState(
    val session: SessionState, val viewedProfileId: LocalProfileId?, val profileExists: Boolean,
    val tx: List<Double?>, val rx: List<Double?>,
    val simulated: Boolean = false,
)

enum class OverviewAction { Connect, Disconnect, RestoreRunning, ViewDevices }

@Composable
fun OverviewScreen(state: OverviewUiState, onAction: (OverviewAction) -> Unit) {
    val applied = state.session.applied.takeIf { it?.saved?.profileId == state.viewedProfileId }
    val ownsOperation = state.profileExists && state.session.requestedProfileId == state.viewedProfileId
    ConfigurationGroup("虚拟网络") {
        Text(if (applied != null || ownsOperation) state.session.phase.label(state.simulated) else "未连接")
        applied?.virtualIpv4?.value?.let { ip ->
            if (applied.freshness == DataFreshness.Stale) Text("上次确认的地址（历史）")
            Text(ip, style = MaterialTheme.typography.titleMedium)
            CopyIpButton(ip)
        }
        val actualCore = state.session.core?.value?.takeIf {
            applied?.freshness == DataFreshness.Fresh && state.session.core?.freshness == DataFreshness.Fresh && it.sessionId == applied.sessionId
        }
        DeviceStatistic("在线设备", if (applied == null) "暂无数据" else actualCore?.peers?.count { it.connections > 0 }?.toString() ?: "未采样")
        if (!state.simulated && actualCore?.peers?.none { it.connections > 0 } == true) Text("等待其他设备加入")
        if (applied != null) Button(onClick = { onAction(OverviewAction.ViewDevices) }) { Text("查看设备") }
        val canDisconnect = ownsOperation && state.session.phase !in listOf(ConnectionPhase.Idle, ConnectionPhase.Failed)
        if (canDisconnect || applied?.freshness == DataFreshness.Fresh) {
            Button(onClick = { onAction(OverviewAction.Disconnect) }, enabled = state.session.phase != ConnectionPhase.Stopping) {
                Text(if (state.session.phase in listOf(
                    ConnectionPhase.Preparing, ConnectionPhase.AwaitingPermission, ConnectionPhase.Connecting,
                    ConnectionPhase.AwaitingAddress, ConnectionPhase.PreparingTun,
                )) "取消连接" else "断开")
            }
        } else Button(
            onClick = { onAction(OverviewAction.Connect) },
            enabled = state.profileExists && state.session.pending == null && state.session.phase !in setOf(ConnectionPhase.Stopping, ConnectionPhase.OutcomeUnknown),
        ) { Text("连接") }
        if (!state.simulated && applied?.freshness == DataFreshness.Stale && state.session.phase == ConnectionPhase.Failed) {
            Button(onClick = { onAction(OverviewAction.RestoreRunning) }) { Text("恢复上次运行内容") }
        }
        ConditionalActionArea(ownsOperation && state.session.failure != null) {
            state.session.failure?.let { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) }
        }
    }
    ConfigurationGroup("实时流量") {
        val tx = if (applied?.freshness == DataFreshness.Fresh) state.tx else emptyList()
        val rx = if (applied?.freshness == DataFreshness.Fresh) state.rx else emptyList()
        DeviceStatistic("上传", rate(tx.lastOrNull()))
        DeviceStatistic("下载", rate(rx.lastOrNull()))
        Text(if (state.simulated) "最近 60 秒 · 模拟趋势" else "最近 60 秒")
        TrafficTrend(tx, rx)
    }
}

/** 固定 60 秒窗口，null 断开曲线，零保留为零；没有交互取值或随机补点。 */
@Composable
private fun TrafficTrend(tx: List<Double?>, rx: List<Double?>) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    val samples = listOf(tx.takeLast(60), rx.takeLast(60))
    val maximum = samples.flatten().filterNotNull().maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
    Canvas(Modifier.fillMaxWidth().height(120.dp).semantics { contentDescription = "最近 60 秒上传下载趋势" }) {
        samples.forEachIndexed { series, points ->
            var previous: Offset? = null
            points.forEachIndexed { index, value ->
                val point = value?.let {
                    Offset((60 - points.size + index) / 59f * size.width, size.height * (1 - it / maximum).toFloat())
                }
                if (point != null) previous?.let { drawLine(colors[series], it, point, strokeWidth = 3.dp.toPx()) }
                previous = point
            }
        }
    }
}

private fun rate(value: Double?) = value?.let { "${it.toLong()} B/s" } ?: "暂无数据"
private fun ConnectionPhase.label(simulated: Boolean) = when (this) {
    ConnectionPhase.Idle -> "未连接"
    ConnectionPhase.Preparing -> "准备中"
    ConnectionPhase.AwaitingPermission -> "等待 VPN 授权"
    ConnectionPhase.Connecting -> "连接中"
    ConnectionPhase.AwaitingAddress -> "等待核心地址"
    ConnectionPhase.PreparingTun -> "准备 VPN 接口"
    ConnectionPhase.Running -> if (simulated) "已连接（模拟）" else "已连接"
    ConnectionPhase.Stopping -> "停止中"
    ConnectionPhase.Reconnecting -> "重新连接中"
    ConnectionPhase.OutcomeUnknown -> "结果未确认"
    ConnectionPhase.Failed -> "连接失败"
}
