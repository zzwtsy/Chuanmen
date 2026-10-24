package com.github.zzwtsy.chuanmen.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], shadows = [RejectingClipboard::class])
class CopyIpButtonTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 系统剪贴板写入抛异常时只显示短错误，不出现成功反馈或异常原文。 */
    @Test fun clipboardFailureShowsOnlyFailureFeedback() {
        ShadowToast.reset()
        compose.setContent { CopyIpButton("10.42.0.2") }
        compose.onNodeWithText("复制 IP").performClick()
        compose.runOnIdle { assertEquals("复制失败", ShadowToast.getTextOfLatestToast()) }
    }
}

@Implements(ClipboardManager::class)
class RejectingClipboard {
    @Implementation fun setPrimaryClip(clip: ClipData) { throw SecurityException("synthetic-platform-failure") }
}
