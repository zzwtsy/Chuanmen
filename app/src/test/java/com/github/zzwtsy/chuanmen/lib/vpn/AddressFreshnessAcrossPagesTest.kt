package com.github.zzwtsy.chuanmen.lib.vpn

import com.github.zzwtsy.chuanmen.features.devices.DevicesViewModel
import com.github.zzwtsy.chuanmen.features.overview.OverviewViewModel
import com.github.zzwtsy.chuanmen.testAppliedSnapshot
import com.github.zzwtsy.chuanmen.testSavedRevision
import org.junit.Assert.*
import org.junit.Test

class AddressFreshnessAcrossPagesTest {
    /** 平台观测失效后，总览和本机设备卡都把旧地址标为历史；新观测恢复后才允许复制。 */
    @Test fun stalePlatformObservationDisablesCurrentAddressAcrossPages() {
        val saved = testSavedRevision()
        val applied = testAppliedSnapshot(saved)
        val attachment = AttachmentId(applied.sessionId, 1)
        val spec = VpnInterfaceSpec(listOf(Ipv4Cidr("10.42.0.1", 24)), listOf(Ipv4Cidr("10.42.0.0", 24)), emptyList(), 1380)
        val runtime = RuntimeSnapshot(applied.sessionId, null, RuntimeStatus.Running, 1, attachment,
            Observation(spec, 1000, DataFreshness.Fresh), Observation(null, null, DataFreshness.Unavailable, UnavailableReason.NoMeasurement))
        val fresh = SessionState(ConnectionPhase.Running, applied.sessionId, saved.profileId, applied,
            runtime = Observation(runtime, 1000, DataFreshness.Fresh))
        assertTrue(fresh.hasCurrentAddress(saved.profileId))
        assertFalse(OverviewViewModel.project(fresh, saved.profileId, true).historicalAddress)
        assertFalse(DevicesViewModel.project(fresh, saved.profileId).local!!.historicalAddress)
        val stale = fresh.copy(runtime = fresh.runtime!!.copy(freshness = DataFreshness.Stale))
        assertFalse(stale.hasCurrentAddress(saved.profileId))
        assertTrue(OverviewViewModel.project(stale, saved.profileId, true).historicalAddress)
        assertTrue(DevicesViewModel.project(stale, saved.profileId).local!!.historicalAddress)
        assertFalse(fresh.hasCurrentAddress(null))
    }
}
