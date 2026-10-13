package com.github.zzwtsy.chuanmen.application

import android.content.ClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.github.zzwtsy.chuanmen.MainActivity
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM 与设备共用实际 demo Activity 的交互契约，不操作真实 VPN 或网络。 */
abstract class DemoNavigationContract {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    /** demo Activity 输入无效 MTU 后切到日志并返回配置，断言 raw 与错误仍可见、仅保存被禁用。 */
    @Test
    fun navigation_preservesInvalidDraft() {
        compose.onNode(hasText("配置") and hasClickAction()).performClick()
        compose.onNodeWithText("完整配置").performClick()
        compose.onNodeWithText("MTU").performScrollTo().performTextReplacement("invalid")
        compose.onNode(hasText("日志") and hasClickAction()).performClick()
        compose.onNode(hasText("配置") and hasClickAction()).performClick()
        compose.onNodeWithText("invalid").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("MTU 请输入 576–9000 的整数").assertIsDisplayed()
        compose.onNodeWithText("仅保存").performScrollTo().assertIsNotEnabled()
    }

    /** 启动 demo 进入设备页并点击复制，断言模拟标记可见且剪贴板实际写入本机合成 IP。 */
    @Test
    fun demoModeAndCopyAction_areVisible() {
        compose.onNodeWithText("演示 · 模拟数据").assertIsDisplayed()
        compose.onNode(hasText("设备") and hasClickAction()).performClick()
        compose.onNodeWithText("10.42.0.1").assertIsDisplayed()
        compose.onNodeWithText("复制 IP").assertIsDisplayed()
        compose.onNodeWithText("复制 IP").performClick()
        compose.runOnIdle {
            val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
            assertEquals("10.42.0.1", clipboard.primaryClip!!.getItemAt(0).text.toString())
        }
    }

    /** 配置页输入无效 MTU 后进入设置并返回，断言原字段、错误与保存禁用状态保留。 */
    @Test
    fun settingsReturn_preservesInvalidDraft() {
        compose.onNode(hasText("配置") and hasClickAction()).performClick()
        compose.onNodeWithText("完整配置").performClick()
        compose.onNodeWithText("MTU").performScrollTo().performTextReplacement("invalid")
        compose.onNode(hasText("设置") and hasClickAction()).performClick()
        compose.onNodeWithText("返回").performScrollTo().performClick()
        compose.onNodeWithText("invalid").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("MTU 请输入 576–9000 的整数").assertIsDisplayed()
        compose.onNodeWithText("仅保存").performScrollTo().assertIsNotEnabled()
    }

    /**
     * 已连接 demo 的 MTU 为 1380；保存 1400 后断言待应用提示和 Apply 按钮可见，
     * 再恢复运行配置，断言字段回到 1380，待应用提示和 Apply 按钮消失。
     */
    @Test
    fun restoreRunningConfiguration_clearsPendingActions() {
        compose.onNode(hasText("配置") and hasClickAction()).performClick()
        compose.onNodeWithText("完整配置").performClick()
        compose.onNodeWithText("MTU").performScrollTo().performTextReplacement("1400")
        compose.onNodeWithText("仅保存").performScrollTo().performClick()
        compose.onNodeWithText("更改待应用").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("应用已保存配置").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("恢复运行配置到已保存").performScrollTo().performClick()
        compose.onNodeWithText("1380").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("更改待应用").assertDoesNotExist()
        compose.onNodeWithText("应用已保存配置").assertDoesNotExist()
    }
}
