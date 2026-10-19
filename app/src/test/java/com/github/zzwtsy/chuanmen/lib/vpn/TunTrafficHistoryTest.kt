package com.github.zzwtsy.chuanmen.lib.vpn

import org.junit.Assert.*
import org.junit.Test

class TunTrafficHistoryTest {
    private val attachment = AttachmentId(SessionId("synthetic-traffic", 1), 1)

    /** 大于 Double 精确范围的累计值先以 Long 求差，零流量保持实测零，首点没有速率。 */
    @Test fun integerDeltaPreservesPrecisionAndMeasuredZero() {
        val history = TunTrafficHistory()
        val baseline = 9_007_199_254_740_993L
        assertNull(history.observe(attachment, 0, TunCounters(baseline, 7, 1, 1)).last().txBytesPerSecond)
        val point = history.observe(attachment, 1_000, TunCounters(baseline + 11, 7, 1, 1)).last()
        assertEquals(11.0, point.txBytesPerSecond!!, 0.0)
        assertEquals(0.0, point.rxBytesPerSecond!!, 0.0)
    }

    /** 快照间隔过长及明确缺测均断开基线，缺失槽位不能插值为零。 */
    @Test fun missingAndLateSnapshotsBreakTheRateBaseline() {
        val history = TunTrafficHistory()
        history.observe(attachment, 0, TunCounters(0, 0, 0, 0))
        val delayed = history.observe(attachment, 5_000, TunCounters(100, 100, 1, 1))
        assertEquals(6, delayed.size)
        assertTrue(delayed.all { it.txBytesPerSecond == null })
        history.observe(attachment, 6_000, null)
        assertNull(history.observe(attachment, 7_000, TunCounters(110, 120, 1, 1)).last().txBytesPerSecond)
    }

    /** 替换 attachment 或计数回退都不能继承旧速率，避免把旧 TUN 混进当前会话。 */
    @Test fun replacementAndCounterResetStartNewBaselines() {
        val history = TunTrafficHistory()
        history.observe(attachment, 0, TunCounters(100, 100, 1, 1))
        assertNull(history.observe(attachment, 1_000, TunCounters(1, 1, 1, 1)).last().txBytesPerSecond)
        val replacement = history.observe(attachment.copy(sequence = 2), 2_000, TunCounters(200, 200, 1, 1))
        assertEquals(1, replacement.size)
        assertNull(replacement.single().txBytesPerSecond)
    }

    /** 持续观测超过一分钟时只保留固定 60 秒窗口，大间隔也不生成无界补点。 */
    @Test fun windowIsBoundedByActualSixtySeconds() {
        val history = TunTrafficHistory()
        repeat(120) { index -> history.observe(attachment, index * 1_000L, TunCounters(index.toLong(), 0, 0, 0)) }
        val latest = history.observe(attachment, 300_000, null)
        assertEquals(60, latest.size)
        assertEquals(241_000L, latest.first().observedAtMillis)
        assertEquals(300_000L, latest.last().observedAtMillis)
        assertTrue(latest.all { it.txBytesPerSecond == null })
    }
}
