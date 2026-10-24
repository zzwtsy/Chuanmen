package com.github.zzwtsy.chuanmen.features.settings

import androidx.lifecycle.ViewModelStore
import com.github.zzwtsy.chuanmen.AppAction
import com.github.zzwtsy.chuanmen.ChuanmenViewModel
import com.github.zzwtsy.chuanmen.lib.Appearance
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.MemoryProfileRepository
import com.github.zzwtsy.chuanmen.lib.profile.ProfileSummary
import com.github.zzwtsy.chuanmen.lib.vpn.RecordingSessions
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import com.github.zzwtsy.chuanmen.testSavedRevision
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    /** 设置与根主题共享唯一偏好；切换查看对象只改变范围说明，不保存或连接，新 owner 恢复默认。 */
    @Test fun appearance_isSharedWithinOwnerAndScopeIsReadOnly() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val first = testSavedRevision()
            val second = testSavedRevision(LocalProfileId("00000000-0000-4000-8000-000000000002"))
                .let { it.copy(vpnPolicy = it.vpnPolicy.copy(includeInternet = true)) }
            val repository = MemoryProfileRepository(listOf(ProfileSummary(first, "A"), ProfileSummary(second, "B")))
            val original = repository.profiles.value
            val sessions = RecordingSessions(SessionState())
            val root = ChuanmenViewModel(repository.profiles, sessions.state, com.github.zzwtsy.chuanmen.testPreferences())
            val settings = SettingsViewModel(repository, root.viewedProfileId, root.appearancePreferences)
            store.put("app", root); store.put("settings", settings)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { root.state.collect {} }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { settings.state.collect {} }
            runCurrent()
            assertEquals("互联网流量：未开启", settings.state.value.internetScope)
            val appearance = Appearance(darkTheme = true, dynamicColor = false)
            settings.setAppearance(appearance)
            root.act(AppAction.OpenSettings)
            root.act(AppAction.Back)
            root.act(AppAction.ViewProfile(second.profileId))
            runCurrent()
            assertEquals(appearance, root.state.value.appearance)
            assertEquals(appearance, root.appearancePreferences.state.value)
            assertEquals(appearance, settings.state.value.appearance)
            assertEquals("互联网流量：已开启", settings.state.value.internetScope)
            assertEquals(original, repository.profiles.value)
            assertTrue(sessions.requests.isEmpty())
            store.clear(); runCurrent()
            val next = ChuanmenViewModel(repository.profiles, sessions.state, com.github.zzwtsy.chuanmen.testPreferences())
            store.put("app", next)
            val nextSettings = SettingsViewModel(repository, next.viewedProfileId, next.appearancePreferences)
            store.put("settings", nextSettings)
            assertNotSame(root.appearancePreferences, next.appearancePreferences)
            assertEquals(Appearance(), nextSettings.state.value.appearance)
            assertEquals(Appearance(), next.state.value.appearance)
        } finally {
            store.clear(); runCurrent(); Dispatchers.resetMain()
        }
    }
}
