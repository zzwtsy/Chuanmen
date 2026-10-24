package com.github.zzwtsy.chuanmen

import com.github.zzwtsy.chuanmen.lib.*
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PreferencesPersistenceTest {
    /** 正式 SharedPreferences 保存后重新创建偏好对象，外观和查看身份恢复且不会生成连接目标。 */
    @Test fun reopen_restoresAppearanceAndLastViewed() = runTest {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("appearance", 0).edit().clear().commit()
        val first = AppearancePreferences.open(context); first.load()
        val appearance = Appearance(true, false)
        val id = LocalProfileId("00000000-0000-4000-8000-000000000002")
        assertTrue(first.update(appearance)); assertTrue(first.view(id))
        val second = AppearancePreferences.open(context); second.load()
        assertEquals(appearance, second.state.value)
        assertEquals(id, second.lastViewed.value)
    }

    /** commit 返回 false 时不发布外观或查看身份，报告失败；显式重试成功后才发布新值。 */
    @Test fun failedCommit_preservesPublishedValuesUntilRetry() = runTest {
        var stored = PreferenceValues()
        var succeeds = false
        val preferences = AppearancePreferences({ stored }, { if (succeeds) stored = it; succeeds }, StandardTestDispatcher(testScheduler))
        preferences.load()
        assertFalse(preferences.update(Appearance(true, false)))
        assertEquals(Appearance(), preferences.state.value)
        assertNotNull(preferences.error.value)
        val id = LocalProfileId("00000000-0000-4000-8000-000000000003")
        assertFalse(preferences.view(id)); assertNull(preferences.lastViewed.value)
        succeeds = true
        assertTrue(preferences.update(Appearance(true, false))); assertTrue(preferences.view(id))
        assertEquals(stored.appearance, preferences.state.value)
        assertEquals(id, preferences.lastViewed.value)
        assertNull(preferences.error.value)
    }
}
