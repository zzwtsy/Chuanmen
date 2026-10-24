package com.github.zzwtsy.chuanmen.lib.vpn

/** Service 的固定 60 秒窗口；代次/attachment、计数回退和缺测都会切断速率基线。 */
internal class TunTrafficHistory {
    private data class Sample(val attachment: AttachmentId, val time: Long, val counters: TunCounters)
    private var previous: Sample? = null
    private var attachmentId: AttachmentId? = null
    private val points = ArrayDeque<TrafficPoint>()
    private val scale = TrafficScale()
    val upperBound: Double get() = scale.upperBound

    fun reset() { previous = null; attachmentId = null; points.clear(); scale.reset() }

    fun observe(attachment: AttachmentId, now: Long, counters: TunCounters?): List<TrafficPoint> {
        if (attachmentId != attachment) reset()
        attachmentId = attachment
        val old = previous
        val elapsed = old?.let { now - it.time } ?: 0
        val valid = old != null && old.attachment == attachment && counters != null &&
            elapsed in 500..2_000 && counters.txBytes >= old.counters.txBytes && counters.rxBytes >= old.counters.rxBytes
        val tx = if (valid) (counters!!.txBytes - old!!.counters.txBytes).toDouble() * 1_000 / elapsed else null
        val rx = if (valid) (counters!!.rxBytes - old!!.counters.rxBytes).toDouble() * 1_000 / elapsed else null
        val slot = now / 1_000 * 1_000
        if (points.lastOrNull()?.observedAtMillis == slot) points.removeLast()
        val last = points.lastOrNull()?.observedAtMillis
        if (last != null) {
            var missing = maxOf(last + 1_000, slot - 59_000)
            while (missing < slot) { points.add(TrafficPoint(missing, null, null)); missing += 1_000 }
        }
        points.add(TrafficPoint(slot, tx, rx))
        while (points.size > 60 || (points.firstOrNull()?.observedAtMillis ?: slot) < slot - 59_000) points.removeFirst()
        previous = counters?.let { Sample(attachment, now, it) }
        scale.observe(points.toList(), tx != null || rx != null)
        return points.toList()
    }
}

/** 上限由服务采样推进；页面返回直接使用当前上限，不重放后台缩放。 */
internal class TrafficScale {
    var upperBound = 1.0
        private set
    private var belowHalf = 0
    fun reset() { upperBound = 1.0; belowHalf = 0 }
    fun observe(points: List<TrafficPoint>, valid: Boolean) {
        val peak = points.flatMap { listOf(it.txBytesPerSecond, it.rxBytesPerSecond) }
            .filterNotNull().filter { it.isFinite() && it >= 0 }.maxOrNull() ?: 0.0
        if (peak > upperBound) { upperBound = peak; belowHalf = 0 }
        else if (peak >= upperBound / 2) belowHalf = 0
        else if (valid && ++belowHalf >= 10) upperBound = maxOf(1.0, peak, upperBound * 0.9)
    }
}
