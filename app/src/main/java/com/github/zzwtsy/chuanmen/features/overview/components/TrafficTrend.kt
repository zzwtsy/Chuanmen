package com.github.zzwtsy.chuanmen.features.overview.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.zzwtsy.chuanmen.lib.vpn.TrafficPoint
import com.github.zzwtsy.chuanmen.features.overview.rate

/** 固定 60 秒窗口，null 断开曲线，零保留为零；没有交互取值或随机补点。 */
@Composable
internal fun TrafficTrend(points: List<TrafficPoint>, upperBound: Double, currentRates: String) {
    val colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
    val window = points.takeLast(60)
    val maximum = upperBound.takeIf { it.isFinite() && it >= 1 } ?: 1.0
    Column {
        Text("上限 ${rate(maximum)}")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("上传（实线）", color = colors[0])
            Text("下载（虚线）", color = colors[1])
        }
        if (window.none { it.txBytesPerSecond.valid() != null || it.rxBytesPerSecond.valid() != null }) Text("暂无流量样本")
        Canvas(Modifier.fillMaxWidth().height(120.dp).semantics {
            contentDescription = "最近 60 秒上传下载趋势，$currentRates"
        }) {
            repeat(2) { series ->
                trafficSegments(window, maximum, series == 0).forEach { segment ->
                    val offsets = segment.map { Offset(it.x * size.width, it.y * size.height) }
                    offsets.zipWithNext().forEach { (start, end) ->
                        drawLine(colors[series], start, end, strokeWidth = 3.dp.toPx(),
                            pathEffect = if (series == 1) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null)
                    }
                    offsets.forEach { drawCircle(colors[series], radius = 1.5.dp.toPx(), center = it) }
                }
            }
        }
    }
}

private fun Double?.valid(): Double? = this?.takeIf { it.isFinite() && it >= 0 }

internal data class TrafficCoordinate(val x: Float, val y: Float)

/** 按单调时间定位点；缺测、跳秒与非有限值均切断线段，实测零保留在底线。 */
internal fun trafficSegments(points: List<TrafficPoint>, maximum: Double, upload: Boolean): List<List<TrafficCoordinate>> {
    val window = points.takeLast(60)
    val end = window.lastOrNull()?.observedAtMillis ?: return emptyList()
    val upper = maximum.takeIf { it.isFinite() && it >= 1 } ?: 1.0
    val segments = mutableListOf<MutableList<TrafficCoordinate>>()
    var previousTime: Long? = null
    var current: MutableList<TrafficCoordinate>? = null
    window.forEach { point ->
        val value = (if (upload) point.txBytesPerSecond else point.rxBytesPerSecond).valid()
        if (value == null) current = null
        else {
            if (current == null || previousTime?.let { point.observedAtMillis - it != 1000L } == true) {
                current = mutableListOf<TrafficCoordinate>().also(segments::add)
            }
            current!!.add(TrafficCoordinate(((point.observedAtMillis - end + 59000) / 59000f).coerceIn(0f, 1f),
                (1 - (value / upper).coerceIn(0.0, 1.0)).toFloat()))
        }
        previousTime = point.observedAtMillis
    }
    return segments
}
