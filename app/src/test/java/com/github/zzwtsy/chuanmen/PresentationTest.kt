package com.github.zzwtsy.chuanmen

import com.github.zzwtsy.chuanmen.lib.profile.testDocument

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DarkMode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.github.zzwtsy.chuanmen.features.configuration.ConfigurationScreen
import com.github.zzwtsy.chuanmen.features.configuration.ConfigurationUiState
import com.github.zzwtsy.chuanmen.features.configuration.lib.Draft
import com.github.zzwtsy.chuanmen.features.overview.OverviewScreen
import com.github.zzwtsy.chuanmen.features.overview.OverviewUiState
import com.github.zzwtsy.chuanmen.features.overview.OverviewViewModel
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.ProfileSummary
import com.github.zzwtsy.chuanmen.lib.vpn.ConnectionPhase
import com.github.zzwtsy.chuanmen.lib.vpn.OperationFailure
import com.github.zzwtsy.chuanmen.lib.vpn.OperationKind
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import com.github.zzwtsy.chuanmen.lib.vpn.TrafficPoint
import com.github.zzwtsy.chuanmen.lib.vpn.UnavailableReason
import com.github.zzwtsy.chuanmen.ui.theme.ChuanmenTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 每个参数是明确的 fixture/外观条件；共享渲染契约不启动引擎。 */
internal data class PresentationCase(
    val id: String,
    val state: AppUiState,
    val expectedStatus: String,
    val configuration: ConfigurationUiState,
    val overview: OverviewUiState,
    val dark: Boolean = false,
    val fontScale: Float = 1f,
    val dynamicColor: Boolean = false,
    val highContrast: Boolean = false,
) {
    override fun toString() = id
}

@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
internal class PresentationTest(private val case: PresentationCase) {
    // v2 调度保证语义操作在组合和副作用空闲后断言。
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * 400 dp 手机窗口下渲染具名场景及浅/深/大字/高对比/动态色条件，断言真实状态文案、
     * 分组 heading、48 dp 主导航触控尺寸与唯一完整导航名称；进入配置后字段可滚动到达。
     * 导航装饰图标不应进入辅助技术的朗读文本。
     */
    @Test
    fun presentation_preservesStateAndAccessibleActions() {
        var state by mutableStateOf(case.state)
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
                    ChuanmenScreen(state, onAction = { action ->
                        if (action is AppAction.Navigate) {
                            state = state.copy(navigation = state.navigation.copy(destination = action.destination))
                        }
                    }) { destination, _ ->
                        if (destination == Destination.Configuration) ConfigurationScreen(case.configuration) {}
                        else OverviewScreen(case.overview) {}
                    }
                }
            }
        }
        compose.onNodeWithText("串门").assertIsDisplayed()
        compose.onNodeWithText(case.expectedStatus).performScrollTo().assertIsDisplayed()
        if (case.id == "apply-failure") {
            compose.onNodeWithText("10.42.0.1").assertIsDisplayed()
            compose.onNodeWithText("应用配置 失败：核心未接受此次操作，请检查配置后重试")
                .performScrollTo().assertIsDisplayed()
        }
        if (case.id == "no-samples") {
            compose.onAllNodesWithText("暂无数据").assertCountEquals(2)
        }
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
        compose.onNode(hasText("配置") and hasClickAction()).performClick()
        if (case.state.profiles.isEmpty()) {
            compose.onNodeWithText("暂无配置").assertIsDisplayed()
        } else {
            compose.onNode(hasText("网络名称") and hasSetTextAction())
                .performScrollTo().assertIsDisplayed()
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases(): List<PresentationCase> {
            val saved = testSavedRevision()
            val profiles = listOf(ProfileSummary(saved, "网络 A"),
                ProfileSummary(testSavedRevision(LocalProfileId("00000000-0000-4000-8000-000000000002")), "网络 B"))
            val applied = testAppliedSnapshot(saved)
            val connected = SessionState(ConnectionPhase.Running, applied.sessionId, saved.profileId, applied,
                traffic = listOf(null, 0.0, 120.0, 80.0, null, 0.0, 32.0).mapIndexed { index, tx ->
                    TrafficPoint(index.toLong(), tx, tx?.times(2))
                })
            val changed = saved.copy(revision = 2, document = saved.core.copy(mtu = 1400).testDocument())
            fun input(
                id: String, expected: String, session: SessionState = SessionState(), inputs: List<ProfileSummary> = profiles,
                dark: Boolean = false, fontScale: Float = 1f, dynamicColor: Boolean = false, highContrast: Boolean = false,
            ): PresentationCase {
                val viewed = inputs.firstOrNull()?.saved?.profileId
                val root = AppUiState(inputs.map { ProfileChoice(it.saved.profileId, it.displayName) }, AppNavigation(viewedProfileId = viewed))
                return PresentationCase(id, root, expected,
                    ConfigurationUiState.project(inputs, session, viewed, inputs.firstOrNull()?.saved?.let(::Draft)),
                    OverviewViewModel.project(session, viewed, inputs.isNotEmpty()), dark, fontScale, dynamicColor, highContrast)
            }
            return listOf(
                input("empty", "未连接", inputs = emptyList()),
                input("disconnected", "未连接"),
                input("connected-light", "已连接", connected),
                input("connection-failure", "连接失败", SessionState(phase = ConnectionPhase.Failed,
                    requestedProfileId = saved.profileId, failure = OperationFailure(OperationKind.Connect, UnavailableReason.EngineRejected))),
                input("apply-failure", "已连接", connected.copy(failure = OperationFailure(OperationKind.Apply, UnavailableReason.EngineRejected)),
                    profiles.map { if (it.saved.profileId == saved.profileId) it.copy(saved = changed) else it }),
                input("no-samples", "已连接", connected.copy(traffic = listOf(TrafficPoint(0, null, null)))),
                input("connected-dark", "已连接", connected, dark = true),
                input("connected-large-font", "已连接", connected, fontScale = 1.6f),
                input("connected-high-contrast", "已连接", connected, highContrast = true),
                input("connected-dynamic-color", "已连接", connected, dynamicColor = true),
            )
        }
    }
}
