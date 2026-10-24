package com.github.zzwtsy.chuanmen

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.github.zzwtsy.chuanmen.features.configuration.*
import com.github.zzwtsy.chuanmen.features.configuration.lib.Draft
import com.github.zzwtsy.chuanmen.features.devices.*
import com.github.zzwtsy.chuanmen.features.logs.*
import com.github.zzwtsy.chuanmen.features.overview.*
import com.github.zzwtsy.chuanmen.features.settings.*
import com.github.zzwtsy.chuanmen.lib.vpn.*
import com.github.zzwtsy.chuanmen.lib.logging.*
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import com.github.zzwtsy.chuanmen.ui.theme.ChuanmenTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalTestApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w600dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptivePagesUiTest(private val width: Int, private val fontScale: Float, private val dark: Boolean) {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    /** 八种宽度、字体和主题组合下，非空日志连续筛选仍可输入，中继分区及只读详情均可滚动访问。 */
    @Test fun nonEmptyFiltersAndRelayDetailsRemainReachable() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = ViewModelStore()
        val persistence = object : LogPersistence {
            override fun prepare() {}
            override fun append(record: LogRecord) {}
            override suspend fun read(consume: suspend (LogRecord) -> Unit) {}
        }
        try {
            val saved = testSavedRevision()
            val applied = testAppliedSnapshot(saved)
            val core = CoreSnapshot(applied.sessionId, OperationToken(applied.sessionId, 1), 1, RuntimeStatus.Running, null,
                listOf(CorePeer(2, 1, networkRole = PeerNetworkRole.Member), CorePeer(9, 1, networkRole = PeerNetworkRole.Relay)),
                listOf(CoreRoute(2, Ipv4Cidr("10.42.0.2", 32), emptyList(), 1, "synthetic")),
                null, null, null, null, null, 1, 0, 1, 0, 0, localPeerId = 1)
            val sessions = MutableStateFlow(SessionState(ConnectionPhase.Running, applied.sessionId, saved.profileId, applied,
                core = Observation(core, 0, DataFreshness.Fresh)))
            val devices = DevicesViewModel(sessions, MutableStateFlow(saved.profileId)).also { store.put("devices", it) }
            val logs = LogCollector(scope, persistence, MonotonicClock { 0 })
            repeat(40) { index ->
                if (index % 2 == 0) logs.emit(LogSource.App, LogLevel.Info, LogModule.Session, LogCode.Start)
                else logs.diagnostics(CoreDiagnostics(listOf(CoreDiagnostic(0, "core_process", "WARN", "peer", "peer_connect_failed")), 0, 128))
            }
            val model = LogsViewModel(logs).also { store.put("logs", it) }
            var root by mutableStateOf(AppUiState(navigation = AppNavigation(destination = Destination.Logs)))
            compose.setContent {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 900.dp)) then
                    DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.DarkMode(dark)) {
                    ChuanmenTheme(darkTheme = dark, dynamicColor = false) {
                        ChuanmenScreen(root, { action -> if (action is AppAction.Navigate) root = root.copy(navigation = root.navigation.copy(destination = action.destination)) }) { destination, _ ->
                            if (destination == Destination.Logs) LogsRoute(model) else DevicesRoute(devices)
                        }
                    }
                }
            }
            compose.waitUntil(5000) { model.state.value.records.size == 40 }
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex)).performScrollToIndex(0)
            compose.onNode(hasText("App") and hasClickAction()).performScrollTo().performClick()
            val input = compose.onNode(hasText("搜索安全日志消息") and hasSetTextAction())
            input.performScrollTo().assertIsDisplayed().performClick().performTextInput("开始")
            input.assertIsFocused().assertIsDisplayed()
            compose.waitForIdle()
            assertEquals(20, model.state.value.records.size)
            assertFalse(model.state.value.following)
            compose.onNode(hasText("设备") and hasClickAction()).performClick()
            compose.onNodeWithText("中继连接").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("公共中继不计入同网在线设备数").performScrollTo().assertIsDisplayed()
            compose.onAllNodesWithText("设备详情")[1].performScrollTo().performClick()
            compose.onNodeWithText("探测连接").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithText("返回列表").performClick()
            compose.onNodeWithText("中继 9").performScrollTo().assertIsDisplayed()
            compose.onNode(hasText("日志") and hasClickAction()).performClick()
            input.assertIsDisplayed()
            assertEquals("开始", model.state.value.filter.keyword)
        } finally { store.clear(); scope.cancel() }
    }

    /** 八个宽度/字号/主题组合中，五个页面的主要动作均可滚动访问，设备详情返回不改列表身份。 */
    @Test fun primaryActionsRemainReachableAcrossFivePages() {
        val saved = testSavedRevision()
        val profile = com.github.zzwtsy.chuanmen.lib.profile.ProfileSummary(saved, "测试网络")
        var root by mutableStateOf(AppUiState(listOf(ProfileChoice(saved.profileId, "测试网络")), AppNavigation(viewedProfileId = saved.profileId)))
        val identity = DeviceIdentity(SessionId("00000000-0000-4000-8000-000000000001", 1), 2)
        val peer = DeviceCardUiState("远端测试设备", "10.42.0.2", "1", "2.7.0", "1", emptyList(), identity = identity,
            statistics = listOf("邻居连接统计：未采样"))
        var detail by mutableStateOf<DeviceCardUiState?>(null)
        val configuration = ConfigurationUiState.project(listOf(profile), SessionState(), saved.profileId, Draft(saved))
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 900.dp)) then
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.DarkMode(dark)) {
                ChuanmenTheme(darkTheme = dark, dynamicColor = false) {
                    ChuanmenScreen(root, { action ->
                        root = when (action) {
                            is AppAction.Navigate -> root.copy(navigation = root.navigation.copy(destination = action.destination, settingsOpen = false))
                            AppAction.OpenSettings -> root.copy(navigation = root.navigation.copy(settingsOpen = true))
                            AppAction.Back -> root.copy(navigation = root.navigation.copy(settingsOpen = false, destination = Destination.Overview))
                            else -> root
                        }
                    }) { destination, settings ->
                        if (settings) SettingsScreen(SettingsUiState(), {}, {})
                        else when (destination) {
                            Destination.Overview -> OverviewScreen(OverviewUiState(connectEnabled = true)) {}
                            Destination.Configuration -> ConfigurationScreen(configuration) {}
                            Destination.Devices -> DevicesScreen(DevicesUiState(notice = null, peers = listOf(peer), detail = detail)) { action ->
                                when (action) { is DeviceAction.Details -> detail = peer; DeviceAction.CloseDetails -> detail = null; else -> Unit }
                            }
                            Destination.Logs -> LogsScreen(LogsUiState())
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("连接").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("配置") and hasClickAction()).performClick()
        compose.onNode(hasText("网络名称") and hasSetTextAction()).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("设备") and hasClickAction()).performClick()
        compose.onNodeWithText("设备详情").performScrollTo()
        val boundsBefore = compose.onNodeWithText("设备详情").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("设备详情").performClick()
        compose.onNodeWithText("探测连接").performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("返回列表").performClick()
        assertEquals(boundsBefore, compose.onNodeWithText("设备详情").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("相关日志").assertDoesNotExist()
        compose.onNodeWithText("远端测试设备").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("日志") and hasClickAction()).performClick()
        compose.onNode(hasText("搜索安全日志消息") and hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("采集 Debug 日志").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("设置") and hasClickAction()).performClick()
        compose.onNodeWithText("编辑当前网络策略").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("返回").performScrollTo().performClick()
        compose.onNodeWithText("连接").performScrollTo().assertIsDisplayed()
    }

    /** 历史地址的复制禁用；固定趋势图只提供当前速率语义，没有拖动、详情或范围切换动作。 */
    @Test fun historicalAddressAndChartExposeOnlyPermittedActions() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(DpSize(width.dp, 900.dp)) then
                DeviceConfigurationOverride.FontScale(fontScale) then DeviceConfigurationOverride.DarkMode(dark)) {
                ChuanmenTheme(darkTheme = dark) {
                    androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                        OverviewScreen(OverviewUiState(ip = "10.42.0.1", historicalAddress = true,
                            points = listOf(TrafficPoint(0, 0.0, 0.0), TrafficPoint(1000, null, null), TrafficPoint(2000, 10.0, 20.0)),
                            upperBound = 20.0, upload = "10 B/s", download = "20 B/s")) {}
                    }
                }
            }
        }
        compose.onNodeWithText("复制 IP").assertIsNotEnabled()
        compose.onNodeWithContentDescription("最近 60 秒上传下载趋势，上传 10 B/s，下载 20 B/s")
            .performScrollTo().assertIsDisplayed().assertHasNoClickAction()
        compose.onNodeWithText("时间范围").assertDoesNotExist()
        compose.onNodeWithText("流量详情").assertDoesNotExist()
    }

    companion object {
        @JvmStatic @ParameterizedRobolectricTestRunner.Parameters(name = "{0}dp-{1}font-dark={2}")
        fun cases(): List<Array<Any>> = listOf(320, 600).flatMap { width -> listOf(1f, 2f).flatMap { scale ->
            listOf(false, true).map { dark -> arrayOf<Any>(width, scale, dark) }
        } }
    }
}
