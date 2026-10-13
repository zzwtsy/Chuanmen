package com.github.zzwtsy.chuanmen.application

import com.github.zzwtsy.chuanmen.application.demo.DemoFixtures
import com.github.zzwtsy.chuanmen.contracts.SavedRevision
import com.github.zzwtsy.chuanmen.contracts.SessionState
import com.github.zzwtsy.chuanmen.feature.configuration.edit.Draft
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUiStateTest {
    /** A 正在运行时只提高已保存修订号，断言内容相同的配置不待应用。 */
    @Test
    fun sameContentWithNewRevision_isNotPending() {
        val saved = DemoFixtures.savedA.copy(revision = 2)
        assertFalse(state(saved).pendingApplication)
    }

    /** A 正在运行时保存不同 MTU，断言核心内容变化仍待应用。 */
    @Test
    fun changedCore_isPending() {
        val saved = DemoFixtures.savedA.copy(core = DemoFixtures.savedA.core.copy(mtu = 1400))
        assertTrue(state(saved).pendingApplication)
    }

    /** A 正在运行时只修改已保存的互联网路由策略，断言 VPN 策略变化仍待应用。 */
    @Test
    fun changedVpnPolicy_isPending() {
        val saved = DemoFixtures.savedA.copy(vpnPolicy = DemoFixtures.savedA.vpnPolicy.copy(includeInternet = true))
        assertTrue(state(saved).pendingApplication)
    }

    /** 没有实际会话时保存不同 MTU，断言离线配置不显示待应用。 */
    @Test
    fun disconnectedProfile_isNotPending() {
        val saved = DemoFixtures.savedA.copy(core = DemoFixtures.savedA.core.copy(mtu = 1400))
        assertFalse(state(saved).copy(session = SessionState()).pendingApplication)
    }

    /** A 正在运行且有待应用内容时切换查看 B，断言另一网络不继承 A 的待应用提示。 */
    @Test
    fun viewingAnotherProfile_isNotPending() {
        val saved = DemoFixtures.savedA.copy(core = DemoFixtures.savedA.core.copy(mtu = 1400))
        val state = state(saved)
        assertFalse(state.copy(navigation = state.navigation.copy(viewedProfileId = DemoFixtures.profileB)).pendingApplication)
    }

    /** A 正在运行时只修改本地显示名称，断言改名不产生待应用状态。 */
    @Test
    fun localRename_isNotPending() {
        val state = state()
        val renamed = state.profiles.map { profile ->
            if (profile.saved.profileId == DemoFixtures.profileA) profile.copy(displayName = "家里的网络") else profile
        }
        assertFalse(state.copy(profiles = renamed).pendingApplication)
    }

    /** A 正在运行时只编辑未保存的 MTU 草稿，断言草稿为脏且已保存配置不待应用。 */
    @Test
    fun unsavedDraft_isNotPending() {
        val state = state().copy(draft = Draft(DemoFixtures.savedA, rawMtu = "1400"))
        assertTrue(state.draft!!.dirty)
        assertFalse(state.pendingApplication)
    }

    private fun state(saved: SavedRevision = DemoFixtures.savedA) = AppUiState(
        profiles = DemoFixtures.profiles.map { profile ->
            if (profile.saved.profileId == saved.profileId) profile.copy(saved = saved) else profile
        },
        navigation = AppNavigation(viewedProfileId = DemoFixtures.profileA),
        draft = Draft(saved),
        session = DemoFixtures.connected.initialSession,
    )
}
