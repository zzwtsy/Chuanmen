package com.github.zzwtsy.chuanmen.features.overview

import com.github.zzwtsy.chuanmen.features.overview.components.trafficSegments
import com.github.zzwtsy.chuanmen.lib.vpn.TrafficPoint
import org.junit.Assert.*
import org.junit.Test

class TrafficTrendTest {
    /** 上传缺测和跳秒均切断实际绘图线段；下载有效点按时间保持连续，不按列表位置压缩横轴。 */
    @Test fun missingValuesBreakOnlyTheirOwnSeriesAndTimeGapsStayVisible() {
        val points = listOf(TrafficPoint(0, 10.0, 10.0), TrafficPoint(1000, null, 0.0), TrafficPoint(2000, 10.0, 10.0), TrafficPoint(4000, 0.0, 0.0))
        val tx = trafficSegments(points, 10.0, true)
        val rx = trafficSegments(points, 10.0, false)
        assertEquals(listOf(1, 1, 1), tx.map { it.size })
        assertEquals(listOf(3, 1), rx.map { it.size })
        assertEquals(1f, tx.last().single().y)
        assertEquals(1f, tx.last().single().x)
        assertEquals(2 / 59f, tx.last().single().x - tx[1].single().x, 0.00001f)
    }

    /** 非有限值不产生 Canvas 坐标或假零线，空窗口无点；有效零仍绘制在底线。 */
    @Test fun nonFiniteValuesAreMissingAndMeasuredZeroRemains() {
        val points = listOf(TrafficPoint(0, Double.NaN, null), TrafficPoint(1000, Double.POSITIVE_INFINITY, null), TrafficPoint(2000, 0.0, null))
        val segments = trafficSegments(points, Double.NaN, true)
        assertEquals(1, segments.size)
        assertEquals(1f, segments.single().single().y)
        assertTrue(trafficSegments(emptyList(), 1.0, true).isEmpty())
    }
}
