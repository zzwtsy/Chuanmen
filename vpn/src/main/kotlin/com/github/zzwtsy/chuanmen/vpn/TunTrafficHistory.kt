package com.github.zzwtsy.chuanmen.vpn

import com.github.zzwtsy.chuanmen.contracts.AttachmentId
import com.github.zzwtsy.chuanmen.contracts.TrafficPoint
import com.github.zzwtsy.chuanmen.contracts.TunCounters

/** Service 的固定 60 秒窗口；代次/attachment、计数回退和缺测都会切断速率基线。 */
internal class TunTrafficHistory {
    private data class Sample(val attachment: AttachmentId, val time: Long, val counters: TunCounters)
    private var previous: Sample? = null
    private val points = ArrayDeque<TrafficPoint>()

    fun reset() { previous = null; points.clear() }

    fun observe(attachment: AttachmentId, now: Long, counters: TunCounters?): List<TrafficPoint> {
        val old = previous
        if (old != null && old.attachment != attachment) reset()
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
        return points.toList()
    }
}
