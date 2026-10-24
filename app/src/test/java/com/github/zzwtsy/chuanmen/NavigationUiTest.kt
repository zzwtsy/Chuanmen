package com.github.zzwtsy.chuanmen

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import com.github.zzwtsy.chuanmen.features.configuration.ConfigurationViewModel
import com.github.zzwtsy.chuanmen.features.devices.DevicesViewModel
import com.github.zzwtsy.chuanmen.features.logs.LogsViewModel
import com.github.zzwtsy.chuanmen.features.overview.OverviewViewModel
import com.github.zzwtsy.chuanmen.features.settings.SettingsViewModel
import com.github.zzwtsy.chuanmen.lib.Appearance
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.MemoryProfileRepository
import com.github.zzwtsy.chuanmen.lib.profile.ProfileSummary
import com.github.zzwtsy.chuanmen.lib.profile.testDocument
import com.github.zzwtsy.chuanmen.lib.vpn.ConnectionPhase
import com.github.zzwtsy.chuanmen.lib.vpn.DataFreshness
import com.github.zzwtsy.chuanmen.lib.vpn.RecordingSessions
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** JVM 上验证真实 ViewModel 的界面交互；固定输入不启动 VPN 或网络。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationUiTest {
    // v2 使用排队调度；语义操作和 runOnIdle 在断言前同步组合与副作用。
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val store get() = compose.activity.viewModelStore
    private lateinit var model: ChuanmenViewModel
    private lateinit var factory: ViewModelProvider.Factory
    private lateinit var repository: MemoryProfileRepository
    private lateinit var sessions: RecordingSessions
    private val creations = mutableMapOf<Class<*>, Int>()

    @Before fun showApplication() {
        val saved = testSavedRevision()
        val applied = testAppliedSnapshot(saved)
        val profiles = listOf(
            ProfileSummary(saved, "网络 A"),
            ProfileSummary(testSavedRevision(LocalProfileId("00000000-0000-4000-8000-000000000002")), "网络 B"),
        )
        sessions = RecordingSessions(SessionState(
            phase = ConnectionPhase.Running, sessionId = applied.sessionId,
            requestedProfileId = saved.profileId, applied = applied,
        ))
        repository = MemoryProfileRepository(profiles)
        model = ChuanmenViewModel(repository.profiles, sessions.state, com.github.zzwtsy.chuanmen.testPreferences())
        factory = object : ViewModelProvider.Factory {
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                creations[modelClass] = (creations[modelClass] ?: 0) + 1
                val feature = when (modelClass) {
                    ConfigurationViewModel::class.java -> ConfigurationViewModel(repository, sessions, model.viewedProfileId, com.github.zzwtsy.chuanmen.lib.profile.TestCoreConfigCodec(), kotlinx.coroutines.Dispatchers.Main.immediate)
                    DevicesViewModel::class.java -> DevicesViewModel(sessions.state, model.viewedProfileId)
                    LogsViewModel::class.java -> LogsViewModel((compose.activity.application as ChuanmenApplication).logs)
                    SettingsViewModel::class.java -> SettingsViewModel(repository, model.viewedProfileId, model.appearancePreferences)
                    OverviewViewModel::class.java -> OverviewViewModel(repository, sessions, model.viewedProfileId)
                    else -> error("未知测试功能")
                }
                @Suppress("UNCHECKED_CAST")
                return feature as T
            }
        }
        store.put("app", model)
        compose.setContent { ChuanmenApp(model, factory) }
    }

    @After fun releaseModel() { compose.runOnIdle { store.clear() } }

    /** 已连接快照下输入无效 MTU 后切到日志并返回配置，断言 raw 与错误仍可见、仅保存被禁用。 */
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

    /** 完整表单输入非法静态地址后搜索 DNS，再清搜索和切标签，地址原文及错误始终保留。 */
    @Test fun fieldSearch_preservesAddressAndErrorsAcrossTabs() {
        compose.onNode(hasText("配置") and hasClickAction()).performClick()
        compose.onNodeWithText("完整配置").performClick()
        compose.onNodeWithText("自动分配 IPv4").performScrollTo().performClick()
        compose.onNodeWithText("静态 IPv4").performScrollTo().performTextReplacement("10.42.0.1/33")
        compose.onNodeWithText("搜索配置项").performScrollTo().performTextReplacement("  DNS  ")
        compose.onNodeWithText("DNS IPv4").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("静态 IPv4").assertDoesNotExist()
        compose.onNodeWithText("清空").performScrollTo().performClick()
        compose.onNodeWithText("10.42.0.1/33").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("基础配置").performScrollTo().performClick()
        compose.onNodeWithText("完整配置").performScrollTo().performClick()
        compose.onNodeWithText("10.42.0.1/33").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("仅保存").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertTrue(sessions.requests.isEmpty()) }
    }

    /** A 运行但查看 B，从设置编辑 B 策略后返回，saved 摘要不变；再次进入仍使用原 dirty 草稿。 */
    @Test fun settingsPolicyEntry_targetsViewedDraftAndDoesNotSaveOnReturn() {
        compose.onNodeWithText("网络 A").performClick()
        compose.onNodeWithText("网络 B").performClick()
        compose.onNode(hasText("设置") and hasClickAction()).performClick()
        compose.onNodeWithText("编辑当前网络策略").performScrollTo().performClick()
        compose.onNodeWithText("包含互联网流量").performScrollTo().performClick()
        compose.onNodeWithText("DNS IPv4").performScrollTo().performTextReplacement("10.42.0.53")
        compose.onNode(hasText("设置") and hasClickAction()).performClick()
        compose.onNodeWithText("互联网流量：未开启").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("当前网络有未保存的策略或配置更改").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("编辑当前网络策略").performScrollTo().performClick()
        compose.onNodeWithText("包含互联网流量").performScrollTo().assertIsOn()
        compose.onNodeWithText("10.42.0.53").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertTrue(repository.profiles.value.all { !it.saved.vpnPolicy.includeInternet })
            assertTrue(sessions.requests.isEmpty())
        }
    }

    /** 从共享配置选择区创建后取消，不留下空配置，也不改变当前查看或会话。 */
    @Test fun creationCancel_doesNotWriteOrConnect() {
        val original = repository.profiles.value
        compose.onNodeWithText("网络 A").performClick()
        compose.onNodeWithText("新建配置").performClick()
        compose.onNodeWithText("网络名称").performScrollTo().performTextReplacement("unsaved-create")
        compose.onNodeWithText("取消创建").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(original, repository.profiles.value)
            assertEquals(original.first().saved.profileId, model.viewedProfileId.value)
            assertTrue(sessions.requests.isEmpty())
        }
    }

    /** 外部保存导致冲突时，保留动作不换基准；重新载入必须确认，取消保留草稿，确认后才使用新版本。 */
    @Test fun conflictReload_requiresConfirmationAndKeepDoesNotOverwrite() {
        compose.onNode(hasText("配置") and hasClickAction()).performClick()
        compose.onNodeWithText("完整配置").performClick()
        compose.onNodeWithText("MTU").performScrollTo().performTextReplacement("1400")
        compose.runOnIdle {
            val saved = repository.profiles.value.first().saved
            kotlinx.coroutines.runBlocking { repository.save(saved.profileId, saved.core.copy(mtu = 1450).testDocument(), saved.vpnPolicy, saved.revision) }
        }
        compose.onNodeWithText("仅保存").performScrollTo().performClick()
        compose.onNodeWithText("保留当前草稿").performScrollTo().performClick()
        compose.onNodeWithText("1400").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("仅保存").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("重新载入已保存版本").performScrollTo().performClick()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("1400").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重新载入已保存版本").performScrollTo().performClick()
        compose.onNodeWithText("确认").performClick()
        compose.onNodeWithText("1450").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("仅保存").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1450, repository.profiles.value.first().saved.core.mtu)
            assertTrue(sessions.requests.isEmpty())
        }
    }

    /** 固定已连接快照进入设备页并复制，断言产品名可见且剪贴板写入合成 IP。 */
    @Test
    fun copyAction_writesAddress() {
        compose.onNodeWithText("串门").assertIsDisplayed()
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
     * 已连接快照的 MTU 为 1380；保存 1400 后断言待应用提示和 Apply 按钮可见，
     * 再请求恢复运行版本，拒绝回执后断言 saved 与待应用提示保留，不伪报恢复成功。
     */
    @Test
    fun restoreRunningConfiguration_waitsForServiceReceipt() {
        compose.onNode(hasText("配置") and hasClickAction()).performClick()
        compose.onNodeWithText("完整配置").performClick()
        compose.onNodeWithText("MTU").performScrollTo().performTextReplacement("1400")
        compose.onNodeWithText("仅保存").performScrollTo().performClick()
        compose.onNodeWithText("更改待应用").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("应用已保存配置").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("恢复上次运行版本").performScrollTo().performClick()
        compose.onNodeWithText("1400").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("更改待应用").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, sessions.restoreRequests) }
    }
    /** 配置页确认本地改名只更新名称；保存修订、applied 与会话请求保持不变。 */
    @Test fun rename_keepsSavedAndAppliedIdentity() {
        val saved = repository.profiles.value.first().saved
        val applied = sessions.state.value.applied
        compose.onNode(hasText("配置") and hasClickAction()).performClick()
        compose.onNodeWithText("修改本地名称").performScrollTo().performClick()
        compose.onNodeWithText("配置名称（可选）").performTextReplacement(" 家里的网络 ")
        compose.onNodeWithText("确认改名").performClick()
        compose.onNodeWithText("家里的网络").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(saved, repository.findSaved(saved.profileId))
            assertEquals(applied, sessions.state.value.applied)
            assertTrue(sessions.requests.isEmpty())
        }
    }

    /** 同一 Activity owner 配置重建后五个功能实例、草稿和外观均保留；不重复创建编辑器或客户端边界。 */
    @Test fun activityRecreation_retainsFeatureOwnersDraftAndAppearance() {
        compose.onNode(hasText("配置") and hasClickAction()).performClick()
        compose.onNodeWithText("完整配置").performClick()
        compose.onNodeWithText("MTU").performScrollTo().performTextReplacement("invalid")
        val owner = compose.activity
        val provider = ViewModelProvider(owner, factory)
        val keys = listOf("configuration" to ConfigurationViewModel::class.java, "overview" to OverviewViewModel::class.java,
            "devices" to DevicesViewModel::class.java, "logs" to LogsViewModel::class.java, "settings" to SettingsViewModel::class.java)
        val before = keys.associate { (key, type) -> key to provider.get(key, type) }
        val preferences = model.appearancePreferences
        compose.runOnIdle {
            (before.getValue("settings") as SettingsViewModel).setAppearance(Appearance(darkTheme = true, dynamicColor = false))
        }
        compose.waitForIdle()
        val counts = creations.toMap()
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.onActivity { recreated ->
            assertNotSame(owner, recreated)
            val retained = ViewModelProvider(recreated, factory)
            assertSame(model, retained.get("app", ChuanmenViewModel::class.java))
            keys.forEach { (key, type) -> assertSame(before.getValue(key), retained.get(key, type)) }
            assertSame(preferences, model.appearancePreferences)
            recreated.setContent { ChuanmenApp(model, factory) }
        }
        compose.onNodeWithText("invalid").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("MTU 请输入 576–9000 的整数").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(Appearance(darkTheme = true, dynamicColor = false), model.state.value.appearance)
            assertEquals(counts, creations)
            assertTrue(creations.values.all { it == 1 })
            assertTrue(sessions.requests.isEmpty())
        }
    }

    /** A 的运行结果未知后查看 B，顶栏只显示历史摘要，设备页不串用 A 地址或在线观测。 */
    @Test fun unknownRunningSummary_doesNotBecomeOtherProfilesDeviceData() {
        compose.runOnIdle {
            sessions.state.value = sessions.state.value.copy(phase = ConnectionPhase.OutcomeUnknown,
                applied = sessions.state.value.applied!!.copy(freshness = DataFreshness.Stale))
        }
        compose.onNodeWithText("网络 A").performClick()
        compose.onNodeWithText("网络 B").performClick()
        compose.onNodeWithText("另一个网络的运行结果未确认：网络 A").assertIsDisplayed()
        compose.onNode(hasText("设备") and hasClickAction()).performClick()
        compose.onNodeWithText("当前查看网络未连接").assertIsDisplayed()
        compose.onNodeWithText("10.42.0.1").assertDoesNotExist()
        compose.runOnIdle { assertTrue(sessions.requests.isEmpty()) }
    }

}
