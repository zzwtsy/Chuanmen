package com.github.zzwtsy.chuanmen.features.overview.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 固定 60 秒窗口，null 断开曲线，零保留为零；没有交互取值或随机补点。 */
@Composable
internal fun TrafficTrend(tx: List<Double?>, rx: List<Double?>) {
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
