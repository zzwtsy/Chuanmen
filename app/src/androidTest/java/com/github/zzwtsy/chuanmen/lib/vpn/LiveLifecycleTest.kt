package com.github.zzwtsy.chuanmen.lib.vpn

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.zzwtsy.chuanmen.ChuanmenApplication
import com.github.zzwtsy.chuanmen.MainActivity
import com.github.zzwtsy.chuanmen.lib.profile.CoreInstanceId
import com.github.zzwtsy.chuanmen.lib.profile.Ipv4Assignment
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.NetworkSecret
import com.github.zzwtsy.chuanmen.lib.profile.PeerEndpoint
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 新建模拟器的正常授权与正式 Service 生命周期；不把无对端的运行当成数据通路通过。 */
@RunWith(AndroidJUnit4::class)
class LiveLifecycleTest {
    /** 用户拒绝和启动中取消均无核心会话；随后正常授权、真实 TUN 安装和停止必须确认。 */
    @Test fun permissionAndServiceLifecycleAreConfirmed() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as ChuanmenApplication
        val repository = app.profiles
        repository.load()
        val creation = controlledInput(CoreInstanceId("p02-lifecycle"), "chuanmen-p02-controlled", NetworkSecret.of("p02-synthetic-public-test-only"), listOf(PeerEndpoint("tcp://127.0.0.1:9")), Ipv4Assignment.Static("10.222.0.2"), emptyList())
        val initialProfile = repository.create(creation.first.controlledDocument(), creation.second, "平台生命周期").requireSuccess()
        val saved = initialProfile
        val report = JSONObject().put("status", "failed").put("scope", "platform_lifecycle_without_peer")
        var activity: ActivityScenario<MainActivity>? = null
        try {
            check(!app.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "DEVICE_LOCKED_BEFORE_ACTIVITY" }
            check(VpnService.prepare(app) != null) { "FRESH_AUTHORIZATION_REQUIRED" }
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

            assertTrue(withContext(Dispatchers.Main) { app.sessions.connect(saved) } is OperationResult.Accepted)
            clickVpnDialog("android:id/button2")
            await(app) { it.phase == ConnectionPhase.Failed }
            assertEquals(UnavailableReason.PermissionDenied, app.sessions.state.value.failure!!.reason)
            assertNull(app.sessions.state.value.applied)
            assertNotNull(VpnService.prepare(app))
            report.put("denial_without_core", true)

            assertTrue(withContext(Dispatchers.Main) { app.sessions.connect(saved) } is OperationResult.Accepted)
            await(app) { it.phase == ConnectionPhase.AwaitingPermission }
            withContext(Dispatchers.Main) { app.sessions.disconnect() }
            clickVpnDialog("android:id/button1")
            withTimeout(10_000) { while (VpnService.prepare(app) != null) delay(100) }
            if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                clickSystemButton(setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller"), ":id/permission_allow_button")
            }
            delay(500)
            assertEquals(ConnectionPhase.Idle, app.sessions.state.value.phase)
            assertNull(app.sessions.state.value.sessionId)
            assertNull(app.sessions.state.value.applied)
            assertEquals(0, withContext(Dispatchers.Main) { app.sessions.resources() }!!.activeSessions)
            report.put("cancel_ignores_late_grant", true)

            assertTrue(withContext(Dispatchers.Main) { app.sessions.connect(saved) } is OperationResult.Accepted)
            val running = await(app) { it.phase == ConnectionPhase.Running && it.core?.value?.attachmentState == TunBorrowState.Installed }
            assertEquals("10.222.0.2", running.applied!!.virtualIpv4.value)
            assertEquals(DataFreshness.Fresh, running.applied!!.freshness)
            assertEquals(0L, running.core!!.value!!.protectionFailures)
            activity.recreate()
            activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            assertEquals(running.sessionId, app.sessions.state.value.sessionId)
            report.put("running_confirmed", true).put("actual_ipv4", running.applied!!.virtualIpv4.value)
                .put("activity_recreation_preserved_session", true).put("generation", running.sessionId!!.generation)
            withContext(Dispatchers.Main) { app.sessions.disconnect() }
            await(app) { it.phase == ConnectionPhase.Idle }
            val resources = withContext(Dispatchers.Main) { app.sessions.resources() }!!
            assertEquals(0, resources.activeSessions)
            assertEquals(0, resources.ownedTunDescriptors)
            delay(1_200)
            assertEquals(ConnectionPhase.Idle, app.sessions.state.value.phase)
            report.put("active_sessions_after_stop", resources.activeSessions)
                .put("owned_pfds_after_stop", resources.ownedTunDescriptors).put("manual_stop_remains_idle", true)
                .put("status", "passed")
        } finally {
            withContext(Dispatchers.Main) { app.sessions.disconnect() }
            try { await(app) { it.phase == ConnectionPhase.Idle } }
            finally {
                app.openFileOutput("p02-lifecycle-report.json", android.content.Context.MODE_PRIVATE).use { it.write(report.toString(2).toByteArray()) }
                activity?.close()
            }
        }
    }

    /** 只点击当前系统 VPN 对话框中的明确按钮，不修改 AppOps 或绕过 VpnService.prepare。 */
    private suspend fun clickVpnDialog(buttonId: String) = clickSystemButton(setOf("com.android.vpndialogs"), buttonId)

    private suspend fun clickSystemButton(packages: Set<String>, buttonSuffix: String) = withTimeout(10_000) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        while (true) {
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() in packages) {
                // Google 系统应用的运行包名与资源包名可能不同，分别核对已知的完整资源 ID。
                val ids = if (buttonSuffix.startsWith("android:")) listOf(buttonSuffix) else packages.map { it + buttonSuffix }
                val button = ids.flatMap { root!!.findAccessibilityNodeInfosByViewId(it) }.singleOrNull()
                if (button != null && button.isEnabled && button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return@withTimeout
            }
            delay(100)
        }
    }

    private suspend fun await(app: ChuanmenApplication, predicate: (SessionState) -> Boolean): SessionState = withTimeout(45_000) {
        while (!predicate(app.sessions.state.value)) delay(100)
        app.sessions.state.value
    }
}
