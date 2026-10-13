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
)

enum class OverviewAction { Connect, Disconnect, ViewDevices }

@Composable
fun OverviewScreen(state: OverviewUiState, onAction: (OverviewAction) -> Unit) {
    val applied = state.session.applied.takeIf { it?.saved?.profileId == state.viewedProfileId }
    val ownsOperation = state.profileExists && state.session.requestedProfileId == state.viewedProfileId
    ConfigurationGroup("虚拟网络") {
        Text(if (applied != null || ownsOperation) state.session.phase.label() else "未连接")
        applied?.virtualIpv4?.value?.let { ip ->
            Text(ip, style = MaterialTheme.typography.titleMedium)
            CopyIpButton(ip)
        }
        DeviceStatistic("在线设备", if (applied == null) "暂无数据" else "未采样")
        if (applied != null) Button(onClick = { onAction(OverviewAction.ViewDevices) }) { Text("查看设备") }
        val canDisconnect = ownsOperation && state.session.phase !in listOf(ConnectionPhase.Idle, ConnectionPhase.Failed)
        if (canDisconnect || applied != null) {
            Button(onClick = { onAction(OverviewAction.Disconnect) }, enabled = state.session.phase != ConnectionPhase.Stopping) {
                Text(if (state.session.phase in listOf(ConnectionPhase.Preparing, ConnectionPhase.Connecting)) "取消连接" else "断开")
            }
        } else Button(
            onClick = { onAction(OverviewAction.Connect) },
            enabled = state.profileExists && state.session.applied == null && state.session.pending == null,
        ) { Text("连接") }
        ConditionalActionArea(ownsOperation && state.session.failure != null) {
            state.session.failure?.let { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) }
        }
    }
    ConfigurationGroup("实时流量") {
        val tx = if (applied != null) state.tx else emptyList()
        val rx = if (applied != null) state.rx else emptyList()
        DeviceStatistic("上传", rate(tx.lastOrNull()))
        DeviceStatistic("下载", rate(rx.lastOrNull()))
        Text("最近 60 秒 · 模拟趋势")
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
private fun ConnectionPhase.label() = when (this) {
    ConnectionPhase.Idle -> "未连接"
    ConnectionPhase.Preparing -> "准备中"
    ConnectionPhase.Connecting -> "连接中"
    ConnectionPhase.Running -> "已连接（模拟）"
    ConnectionPhase.Stopping -> "停止中"
    ConnectionPhase.Failed -> "连接失败"
}
