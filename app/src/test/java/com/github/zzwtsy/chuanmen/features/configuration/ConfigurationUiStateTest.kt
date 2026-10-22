package com.github.zzwtsy.chuanmen.features.configuration

import com.github.zzwtsy.chuanmen.features.configuration.lib.Draft
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.ProfileSummary
import com.github.zzwtsy.chuanmen.lib.profile.SavedRevision
import com.github.zzwtsy.chuanmen.lib.vpn.ConnectionPhase
import com.github.zzwtsy.chuanmen.lib.vpn.DataFreshness
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import com.github.zzwtsy.chuanmen.testAppliedSnapshot
import com.github.zzwtsy.chuanmen.testSavedRevision
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigurationUiStateTest {
    private val savedA = testSavedRevision()
    private val savedB = testSavedRevision(LocalProfileId("00000000-0000-4000-8000-000000000002"))
    private val profiles = listOf(ProfileSummary(savedA, "网络 A"), ProfileSummary(savedB, "网络 B"))
    private val applied = testAppliedSnapshot(savedA)
    private val running = SessionState(
        phase = ConnectionPhase.Running, sessionId = applied.sessionId,
        requestedProfileId = savedA.profileId, applied = applied,
    )

    /** A 正在运行时只提高已保存修订号，断言内容相同的配置不待应用。 */
    @Test
    fun sameContentWithNewRevision_isNotPending() {
        val saved = savedA.copy(revision = 2)
        assertFalse(state(saved).pendingApplication)
    }

    /** A 正在运行时保存不同 MTU，断言核心内容变化仍待应用。 */
    @Test
    fun changedCore_isPending() {
        val saved = savedA.copy(core = savedA.core.copy(mtu = 1400))
        assertTrue(state(saved).pendingApplication)
    }

    /** A 正在运行时只修改已保存的互联网路由策略，断言 VPN 策略变化仍待应用。 */
    @Test
    fun changedVpnPolicy_isPending() {
        val saved = savedA.copy(vpnPolicy = savedA.vpnPolicy.copy(includeInternet = true))
        assertTrue(state(saved).pendingApplication)
    }

    /** 没有实际会话时保存不同 MTU，断言离线配置不显示待应用。 */
    @Test
    fun disconnectedProfile_isNotPending() {
        val saved = savedA.copy(core = savedA.core.copy(mtu = 1400))
        assertFalse(state(saved, session = SessionState()).pendingApplication)
    }

    /** A 正在运行且有待应用内容时切换查看 B，断言另一网络不继承 A 的待应用提示。 */
    @Test
    fun viewingAnotherProfile_isNotPending() {
        val saved = savedA.copy(core = savedA.core.copy(mtu = 1400))
        assertFalse(state(saved, viewedId = savedB.profileId).pendingApplication)
    }

    /** A 正在运行时只修改本地显示名称，断言改名不产生待应用状态。 */
    @Test
    fun localRename_isNotPending() {
        val renamed = profiles.map { profile ->
            if (profile.saved.profileId == savedA.profileId) profile.copy(displayName = "家里的网络") else profile
        }
        assertFalse(state(profilesOverride = renamed).pendingApplication)
    }

    /** A 正在运行时只编辑未保存的 MTU 草稿，断言草稿为脏且已保存配置不待应用。 */
    @Test
    fun unsavedDraft_isNotPending() {
        val state = state().copy(draft = Draft(savedA, rawMtu = "1400"))
        assertTrue(state.draft!!.dirty)
        assertFalse(state.pendingApplication)
    }

    /** 应用结果未知时已应用配置仅为历史，即使保存内容不同也不宣称正在查看运行网络或允许待应用。 */
    @Test
    fun historicalApplied_doesNotClaimCurrentRunningConfiguration() {
        val changed = savedA.copy(core = savedA.core.copy(mtu = 1400))
        val unknown = state(changed, session = running.copy(
            phase = ConnectionPhase.OutcomeUnknown,
            applied = running.applied!!.copy(freshness = DataFreshness.Stale),
        ))
        assertFalse(unknown.viewingRunningProfile)
        assertFalse(unknown.pendingApplication)
    }

    private fun state(
        saved: SavedRevision = savedA, session: SessionState = running,
        viewedId: LocalProfileId? = savedA.profileId, profilesOverride: List<ProfileSummary> = profiles,
    ) = ConfigurationUiState.project(
        profilesOverride.map { profile -> if (profile.saved.profileId == saved.profileId) profile.copy(saved = saved) else profile },
        session, viewedId, Draft(saved),
    )
}
