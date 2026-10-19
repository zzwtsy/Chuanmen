package com.github.zzwtsy.chuanmen.vpn

import android.content.Intent
import android.net.VpnService
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.zzwtsy.chuanmen.MainActivity
import com.github.zzwtsy.chuanmen.application.LiveApplication
import com.github.zzwtsy.chuanmen.contracts.*
import com.github.zzwtsy.chuanmen.feature.configuration.controlled.ControlledProfileInput
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 自有英文 API37 模拟器通过正常系统设置 Forget VPN 撤销本测试应用授权。 */
@RunWith(AndroidJUnit4::class)
class LiveSystemRevokeTest {
    /** 实际运行后由系统撤销授权；Service 必须收到撤销并排空资源，不伪调用 onRevoke。 */
    @Test fun systemForgetRevokesActiveServiceAndDrainsResources() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as LiveApplication
        check(android.os.Build.VERSION.SDK_INT == 37)
        val id = LocalProfileId("00000000-0000-4000-8000-000000000256")
        val repository = ControlledProfileInput.create(id, CoreInstanceId("p02-revoke-android"), "系统撤销回归",
            "chuanmen-p02-controlled", NetworkSecret.of("p02-synthetic-public-test-only"),
            listOf(PeerEndpoint("tcp://127.0.0.1:9")), Ipv4Assignment.Static("10.222.0.2"), emptyList())
        withContext(Dispatchers.Main) { app.setRepository(repository) }
        val report = JSONObject().put("status", "failed").put("scope", "api37_owned_emulator_system_forget")
        var activity: ActivityScenario<MainActivity>? = null
        try {
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            assertTrue(withContext(Dispatchers.Main) { app.sessions.connect(repository.findSaved(id)!!) } is OperationResult.Accepted)
            if (VpnService.prepare(app) != null) withTimeout(10_000) {
                val automation = instrumentation.uiAutomation
                automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
                while (VpnService.prepare(app) != null) {
                    val root = automation.rootInActiveWindow
                    if (root?.packageName?.toString() == "com.android.vpndialogs") {
                        root.findAccessibilityNodeInfosByViewId("android:id/button1").singleOrNull()?.let(::click)
                    }
                    delay(100)
                }
            }
            withTimeout(45_000) { while (app.sessions.state.value.phase != ConnectionPhase.Running) delay(100) }
            report.put("running_before_revoke", true)
            // 自有模拟器清理旧设置任务的详情栈，确保本次从 VPN 列表进入。
            app.startActivity(Intent("android.settings.VPN_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val automation = instrumentation.uiAutomation
            automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            val label = app.applicationInfo.loadLabel(app.packageManager).toString()
            withTimeout(10_000) {
                while (true) {
                    val root = automation.rootInActiveWindow
                    if (root?.packageName == "com.android.settings" && root.findAccessibilityNodeInfosByText(label).isNotEmpty()) {
                        val buttons = root.findAccessibilityNodeInfosByViewId("com.android.settings:id/settings_button")
                        // 新建测试模拟器仅有本测试 VPN，避免误操作其他配置。
                        if (buttons.size == 1 && click(buttons.single())) break
                    }
                    delay(100)
                }
            }
            report.put("settings_detail_opened", true)
            withTimeout(10_000) {
                while (true) {
                    val root = automation.rootInActiveWindow
                    if (root?.packageName == "com.android.settings") {
                        val forget = root.findAccessibilityNodeInfosByViewId("android:id/title").singleOrNull { it.text?.toString() == "Forget VPN" }
                        if (forget != null && click(forget)) break
                    }
                    delay(100)
                }
            }
            report.put("forget_action_clicked", true)
            withTimeout(10_000) {
                while (true) {
                    val root = automation.rootInActiveWindow
                    if (root?.packageName == "com.android.settings") {
                        val confirm = root.findAccessibilityNodeInfosByViewId("android:id/button2").singleOrNull()
                        if (confirm != null) {
                            report.put("confirmation_button_text", confirm.text?.toString())
                            report.put("confirmation_dialog_text", root.findAccessibilityNodeInfosByViewId("android:id/alertTitle").map { it.text?.toString() }.joinToString())
                            check(confirm.text?.toString()?.equals("Forget", ignoreCase = true) == true) { "SYSTEM_FORGET_CONFIRMATION_UNAVAILABLE" }
                            if (click(confirm)) break
                        }
                    }
                    delay(100)
                }
            }
            report.put("forget_confirmation_clicked", true)
            withTimeout(15_000) { while (app.sessions.state.value.phase != ConnectionPhase.Failed || app.sessions.state.value.failure?.reason != UnavailableReason.ServiceRevoked) delay(100) }
            assertNotNull(VpnService.prepare(app))
            val resources = withContext(Dispatchers.Main) { app.sessions.resources() }!!
            assertEquals(0, resources.activeSessions)
            assertEquals(0, resources.ownedTunDescriptors)
            assertTrue(app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none { it.id == 2002 })
            delay(1_200)
            assertEquals(ConnectionPhase.Failed, app.sessions.state.value.phase)
            report.put("system_permission_revoked", true).put("service_revoked_confirmed", true)
                .put("active_sessions_after_revoke", 0).put("owned_pfds_after_revoke", 0)
                .put("foreground_notification_removed", true).put("no_automatic_restart", true).put("status", "passed")
        } finally {
            val before = app.sessions.state.value
            report.put("phase_before_cleanup", before.phase.name).put("permission_revoked_before_cleanup", VpnService.prepare(app) != null)
                .put("failure_before_cleanup", before.failure?.reason?.name)
                .put("lifecycle_codes", org.json.JSONArray(before.lifecycle.map { it.code }))
            withContext(Dispatchers.Main) {
                app.sessions.resources()?.let { report.put("active_before_cleanup", it.activeSessions).put("pfds_before_cleanup", it.ownedTunDescriptors) }
            }
            withContext(Dispatchers.Main) { app.sessions.disconnect() }
            try { withTimeout(15_000) { while (app.sessions.state.value.phase != ConnectionPhase.Idle) delay(100) } }
            finally {
                app.openFileOutput("p02-revoke-report.json", android.content.Context.MODE_PRIVATE).use { it.write(report.toString(2).toByteArray()) }
                activity?.close()
            }
        }
    }

    /** 沿已核对的系统节点向上寻找可点击容器，不用坐标或修改授权 AppOps。 */
    private fun click(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            val candidate = current ?: return false
            if (candidate.isClickable && candidate.isEnabled) return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            current = candidate.parent
        }
        return false
    }
}
