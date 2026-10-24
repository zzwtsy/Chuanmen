package com.github.zzwtsy.chuanmen.features.configuration

import android.content.ComponentName
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.github.zzwtsy.chuanmen.ChuanmenApplication
import com.github.zzwtsy.chuanmen.TestChuanmenApplication
import com.github.zzwtsy.chuanmen.MainActivity
import com.github.zzwtsy.chuanmen.lib.profile.Ipv4Assignment
import com.github.zzwtsy.chuanmen.lib.profile.MemoryProfileRepository
import com.github.zzwtsy.chuanmen.lib.vpn.ConnectionPhase
import com.github.zzwtsy.chuanmen.lib.vpn.NativeVpnService
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 保留真实 Activity 与保存入口；仅以测试内存仓库替代设备 Keystore，保存不触发 VPN。 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestChuanmenApplication::class, sdk = [37], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConfigurationEntryUiTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            // 平台不提供控制 Binder；保留真实入口与客户端，避免 Robolectric 默认空组件回调。
            val app = RuntimeEnvironment.getApplication() as ChuanmenApplication

            shadowOf(app).declareComponentUnbindable(ComponentName(app, NativeVpnService::class.java))
        }
    }).around(compose)
    private val application get() = compose.activity.application as ChuanmenApplication

    /** 新进程没有示例配置；文字均空，自动地址开启且互联网路由关闭。 */
    @Test fun initialFields_areEmptyWithoutStartingSession() {
        listOf("本地名称（可选）", "网络名称", "网络密码").forEach { label ->
            assertEquals("", field(label).fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        }
        field("完整配置").performClick()
        compose.onNodeWithText("自动分配 IPv4").performScrollTo().assertIsOn()
        compose.onNodeWithText("包含互联网流量").performScrollTo().assertIsOff()
        compose.onNodeWithText("静态 IPv4").assertIsNotEnabled()
        assertTrue(application.profiles.profiles.value.isEmpty())
        assertNoConnectionStarted()
    }

    /** 无效节点保存失败时保留全部输入；仓库仍为空且不发起 VPN 请求。 */
    @Test fun invalidInput_isRetainedWithoutPublishingRepository() {
        fillRequired("https://192.0.2.10:11010")
        field("保存配置").performClick()
        compose.onNodeWithText("请检查输入错误").performScrollTo().assertIsDisplayed()
        assertEquals("test-network", field("网络名称").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("•".repeat(16), field("网络密码").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("https://192.0.2.10:11010", field("节点 URL 1").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertTrue(application.profiles.profiles.value.isEmpty())
        assertNoConnectionStarted()
        // 密码语义保持遮罩；只修正节点后成功保存，用结果核对原密码没有丢失。
        field("节点 URL 1").performTextReplacement("tcp://192.0.2.10:11010")
        field("保存配置").performClick()
        compose.runOnIdle {
            assertEquals("synthetic-secret", application.profiles.profiles.value.single().saved.core.networkSecret!!.revealForCore())
            assertNoConnectionStarted()
        }
    }

    /** 静态地址原文在模式切换后仍保留；非法地址拒绝保存，改为合法值后保存准确地址。 */
    @Test fun addressMode_retainsRawInputAndValidatesStaticAddress() {
        fillRequired()
        field("完整配置").performClick()
        field("自动分配 IPv4").performClick()
        field("静态 IPv4").performTextReplacement("10.42.invalid")
        field("保存配置").performClick()
        assertTrue(application.profiles.profiles.value.isEmpty())
        field("自动分配 IPv4").performClick()
        compose.onNodeWithText("静态 IPv4").assertIsNotEnabled()
        field("自动分配 IPv4").performClick()
        assertEquals("10.42.invalid", field("静态 IPv4").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        field("静态 IPv4").performTextReplacement("10.42.0.9")
        field("保存配置").performClick()
        compose.runOnIdle {
            assertEquals(Ipv4Assignment.Static("10.42.0.9"), application.profiles.profiles.value.single().saved.core.ipv4)
            assertNoConnectionStarted()
        }
    }

    /** 同一轮排队两次提交只发布一次；空白本地名称回退网络名称，自动模式保存后仍未连接。 */
    @Test fun queuedSubmissions_publishOnlyOnceAndFallbackToNetworkName() {
        fillRequired()
        field("本地名称（可选）").performTextReplacement("  ")
        val submit = field("保存配置").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle { submit(); submit() }
        compose.runOnIdle {
            val saved = application.profiles.profiles.value.single()
            assertEquals("test-network", saved.displayName)
            assertEquals(Ipv4Assignment.Automatic, saved.saved.core.ipv4)
            assertEquals(1380, saved.saved.core.mtu)
            assertFalse(saved.saved.vpnPolicy.includeInternet)
            assertTrue(saved.saved.vpnPolicy.dnsServers.isEmpty())
            assertNoConnectionStarted()
        }
        compose.onNodeWithText("新建配置").assertDoesNotExist()
    }

    /** 空库创建输入、错误、模式和搜索经 Activity 重建仍保留，秘密只留在同一 ViewModel 内。 */
    @Test fun emptyStoreDraft_survivesActivityRecreation() {
        fillRequired()
        field("本地名称（可选）").performTextReplacement("尚未保存")
        field("完整配置").performClick()
        field("MTU").performTextReplacement("invalid-mtu")
        field("保存配置").performClick()
        field("搜索配置项").performTextReplacement("flags.mtu")
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        assertEquals("flags.mtu", field("搜索配置项").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("invalid-mtu", field("MTU").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithText("MTU 请输入 576–9000 的整数").assertExists()
        field("基础配置").performClick()
        assertEquals("尚未保存", field("本地名称（可选）").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("test-network", field("网络名称").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("•".repeat(16), field("网络密码").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("tcp://192.0.2.10:11010", field("节点 URL 1").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertTrue(application.profiles.profiles.value.isEmpty()); assertNoConnectionStarted()
    }

    /** 已有配置时进入新建后重建 Activity，创建路由和未保存输入保持，不产生第二份配置。 */
    @Test fun nonEmptyStoreCreationRoute_survivesActivityRecreation() {
        fillRequired(); field("保存配置").performClick(); compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            androidx.lifecycle.ViewModelProvider(activity, com.github.zzwtsy.chuanmen.AppAssembly.factory)
                .get("app", com.github.zzwtsy.chuanmen.ChuanmenViewModel::class.java)
                .act(com.github.zzwtsy.chuanmen.AppAction.CreateProfile)
        }
        compose.waitForIdle()
        field("网络名称").performTextReplacement("second-unsaved")
        field("本地名称（可选）").performTextReplacement("第二份草稿")
        compose.activityRule.scenario.recreate(); compose.waitForIdle()
        assertEquals("second-unsaved", field("网络名称").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals("第二份草稿", field("本地名称（可选）").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertEquals(1, application.profiles.profiles.value.size)
        field("取消创建").performClick(); compose.onNodeWithText("新建配置").assertDoesNotExist()
        assertEquals(1, application.profiles.profiles.value.size); assertNoConnectionStarted()
    }

    private fun field(label: String) = compose.onNodeWithText(label).performScrollTo()

    private fun fillRequired(peer: String = "tcp://192.0.2.10:11010") {
        field("网络名称").performTextReplacement("test-network")
        field("网络密码").performTextReplacement("synthetic-secret")
        field("添加节点").performClick()
        field("节点 URL 1").performTextReplacement(peer)
    }

    private fun assertNoConnectionStarted() {
        assertEquals(ConnectionPhase.Idle, application.sessions.state.value.phase)
        assertNull(shadowOf(application).nextStartedService)
        assertNull(shadowOf(compose.activity).nextStartedActivityForResult)
    }
}
