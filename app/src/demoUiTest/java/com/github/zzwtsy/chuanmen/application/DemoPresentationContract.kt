package com.github.zzwtsy.chuanmen.application

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.DarkMode
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.github.zzwtsy.chuanmen.application.demo.DemoFixtures
import com.github.zzwtsy.chuanmen.application.demo.DemoScenario
import com.github.zzwtsy.chuanmen.application.demo.uiState
import com.github.zzwtsy.chuanmen.shared.designsystem.theme.ChuanmenTheme
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 每个参数是明确的 fixture/外观条件；共享渲染契约不启动引擎。 */
data class PresentationCase(
    val id: String,
    val scenario: DemoScenario,
    val expectedStatus: String,
    val dark: Boolean = false,
    val fontScale: Float = 1f,
    val dynamicColor: Boolean = false,
    val highContrast: Boolean = false,
) {
    override fun toString() = id
}

@OptIn(ExperimentalTestApi::class)
abstract class DemoPresentationContract(private val case: PresentationCase) {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * 400 dp 手机窗口下渲染具名场景及浅/深/大字/高对比/动态色条件，断言真实状态文案、
     * 分组 heading、48 dp 主导航触控尺寸与唯一完整导航名称；进入配置后字段可滚动到达。
     * API 26+ 保存当前渲染图供人工复核；API 24–25 记录省略截图的原因并执行全部断言。
     * 导航装饰图标不应进入辅助技术的朗读文本。
     */
    @Test
    fun presentation_preservesStateAndAccessibleActions() {
        var state by mutableStateOf(case.scenario.uiState())
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(400.dp, 800.dp)) then
                    DeviceConfigurationOverride.DarkMode(case.dark) then
                    DeviceConfigurationOverride.FontScale(case.fontScale),
            ) {
                ChuanmenTheme(
                    darkTheme = case.dark,
                    dynamicColor = case.dynamicColor,
                    highContrast = case.highContrast,
                ) {
                    ChuanmenScreen(state) { action ->
                        if (action is AppAction.Navigate) {
                            state = state.copy(navigation = state.navigation.copy(destination = action.destination))
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("演示 · 模拟数据").assertIsDisplayed()
        compose.onNodeWithText(case.expectedStatus).performScrollTo().assertIsDisplayed()
        compose.onNode(
            hasText("虚拟网络") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
        ).assertIsDisplayed()
        Destination.entries.forEach { destination ->
            val navigation = compose.onNode(hasText(destination.title) and hasClickAction())
            navigation
                .assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            assertEquals(
                listOf(destination.title),
                navigation.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text },
            )
        }
        capture("overview")
        compose.onNode(hasText("配置") and hasClickAction()).performClick()
        if (case.scenario.profiles.isEmpty()) {
            compose.onNodeWithText("暂无配置").assertIsDisplayed()
        } else {
            compose.onNode(hasText("网络名称") and hasSetTextAction())
                .performScrollTo().assertIsDisplayed()
        }
        capture("configuration")
    }

    private fun capture(page: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.i(
                "P01_UI_CAPTURE",
                "用例=${case.id}，页面=$page，API=${Build.VERSION.SDK_INT}：" +
                    "省略截图，PixelCopy Window 接口要求 API 26+；继续执行 UI 断言。",
            )
            return
        }
        val directory = captureDirectory().apply { mkdirs() }
        directory.resolve("${case.id}-$page.png").outputStream().use { output ->
            check(captureBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
        }
    }

    protected open fun captureDirectory(): File = compose.activity.cacheDir.resolve("p01-ui")

    @RequiresApi(Build.VERSION_CODES.O)
    protected open fun captureBitmap(): Bitmap = compose.onRoot().captureToImage().asAndroidBitmap()

    companion object {
        @JvmStatic
        fun cases(): List<PresentationCase> = listOf(
            PresentationCase("empty", DemoFixtures.emptyNetwork, "未连接"),
            PresentationCase("disconnected", DemoFixtures.disconnected, "未连接"),
            PresentationCase("connected-light", DemoFixtures.connected, "已连接（模拟）"),
            PresentationCase("connection-failure", DemoFixtures.connectionFailure, "连接失败"),
            PresentationCase("apply-failure", DemoFixtures.applyFailure, "已连接（模拟）"),
            PresentationCase("no-samples", DemoFixtures.noSamples, "已连接（模拟）"),
            PresentationCase("connected-dark", DemoFixtures.connected, "已连接（模拟）", dark = true),
            PresentationCase("connected-large-font", DemoFixtures.connected, "已连接（模拟）", fontScale = 1.6f),
            PresentationCase("connected-high-contrast", DemoFixtures.connected, "已连接（模拟）", highContrast = true),
            PresentationCase("connected-dynamic-color", DemoFixtures.connected, "已连接（模拟）", dynamicColor = true),
        )
    }
}
