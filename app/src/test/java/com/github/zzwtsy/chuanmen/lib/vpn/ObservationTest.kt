package com.github.zzwtsy.chuanmen.lib.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ObservationTest {
    /** 同一采样时刻分别构造零计数与缺测，断言零保留有效值而缺测保留原因。 */
    @Test
    fun zeroAndMissing_areDifferent() {
        val zero = Observation(0L, 1000, DataFreshness.Fresh)
        val missing = Observation<Long>(null, 1000, DataFreshness.Unavailable, UnavailableReason.NoMeasurement)
        assertEquals(0L, zero.value)
        assertNull(missing.value)
        assertNotEquals(zero, missing)
        assertEquals(UnavailableReason.NoMeasurement, missing.unavailableReason)
    }

    /** 输入 null 却声明 Fresh 时，构造观测值必须拒绝这种伪造有效数据的组合。 */
    @Test(expected = IllegalArgumentException::class)
    fun missingCannotBeFresh() {
        Observation<Long>(null, null, DataFreshness.Fresh)
    }

    /** 过期观测含最后值与观测时间，构造后仍保留数据并明确标记 Stale。 */
    @Test
    fun stale_retainsLastObservation() {
        val value = Observation(42L, 1000, DataFreshness.Stale)
        assertEquals(42L, value.value)
        assertEquals(1000L, value.observedAtMillis)
        assertEquals(DataFreshness.Stale, value.freshness)
    }
}
