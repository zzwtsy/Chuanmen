package com.github.zzwtsy.chuanmen.lib.vpn

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.view.WindowManager
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
import java.io.IOException
import java.net.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 在已授权真机上短暂切换 Wi-Fi，恢复原启用状态；IPv6 以实际可达对端作为反向判据。 */
@RunWith(AndroidJUnit4::class)
class LiveNetworkPolicyTest {
    /** 物理 IPv6 前后回包而 VPN 期间阻断；真实 Wi-Fi 恢复递增会话，手动断开后再次切网不重启。 */
    @Test fun physicalNetworkRecoveryAndIpv6BlockingAreObserved() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val host = requireNotNull(args.getString("peerHost"))
        val ipv6 = requireNotNull(args.getString("peerIpv6"))
        require(InetAddress.getByName(ipv6) is Inet6Address)
        val runId = requireNotNull(args.getString("runId"))
        val app = instrumentation.targetContext.applicationContext as ChuanmenApplication
        val manager = app.getSystemService(ConnectivityManager::class.java)
        fun wifi(): Network? = manager.allNetworks.firstOrNull { manager.getNetworkCapabilities(it)?.let { cap ->
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } == true }
        val originalWifi = requireNotNull(wifi()) { "PHYSICAL_WIFI_BASELINE_MISSING" }
        check(VpnService.prepare(app) == null) { "VPN_PERMISSION_REQUIRED_BEFORE_NETWORK_CASE" }
        check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
        val repository = app.profiles
        repository.load()
        val creation = controlledInput(CoreInstanceId("p02-network-android"), "chuanmen-p02-controlled", NetworkSecret.of("p02-synthetic-public-test-only"), listOf(PeerEndpoint("tcp://$host:31270")), Ipv4Assignment.Static("10.222.0.2"), emptyList())
        val initialProfile = repository.create(creation.first.controlledDocument(), creation.second, "物理网络回归").requireSuccess()
        val id = initialProfile.profileId
        val requests = JSONArray()
        val blocked = JSONArray()
        val report = JSONObject().put("run_id", runId).put("status", "failed").put("requests", requests).put("blocked_requests", blocked)
        var activity: ActivityScenario<MainActivity>? = null
        var wifiDisabled = false
        suspend fun shell(command: String) = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
            Unit
        }
        suspend fun physical(stage: String) {
            for (protocol in listOf("tcp", "udp")) {
                val payload = "$runId:$stage:ipv6-$protocol:${System.nanoTime()}\n".toByteArray()
                val response = withContext(Dispatchers.IO) { ipv6Roundtrip(ipv6, protocol, payload) }
                assertArrayEquals(payload, response)
                requests.put(JSONObject().put("stage", stage).put("protocol", "ipv6-$protocol").put("bytes", payload.size)
                    .put("request_sha256", ControlledDatapathProbe.sha(payload)).put("response_sha256", ControlledDatapathProbe.sha(response)))
            }
        }
        suspend fun business(stage: String) {
            for (protocol in listOf("tcp", "udp")) {
                val payload = "$runId:$stage:$protocol:${System.nanoTime()}\n".toByteArray()
                val response = withContext(Dispatchers.IO) { ControlledDatapathProbe.roundtrip(protocol, payload) }
                assertArrayEquals(payload, response)
                requests.put(JSONObject().put("stage", stage).put("protocol", protocol).put("bytes", payload.size)
                    .put("request_sha256", ControlledDatapathProbe.sha(payload)).put("response_sha256", ControlledDatapathProbe.sha(response)))
            }
        }
        try {
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            physical("before-vpn")
            assertTrue(withContext(Dispatchers.Main) { app.sessions.connect(repository.findSaved(id)!!) } is OperationResult.Accepted)
            val initial = await(app) { it.phase == ConnectionPhase.Running && it.core?.value?.peers?.any { p -> p.connections > 0 } == true }
            business("initial")
            for (protocol in listOf("tcp", "udp")) {
                val payload = "$runId:blocked:ipv6-$protocol:${System.nanoTime()}\n".toByteArray()
                var denied = false
                try { withContext(Dispatchers.IO) { ipv6Roundtrip(ipv6, protocol, payload) } }
                catch (_: IOException) { denied = true }
                assertTrue("IPV6_ESCAPED_VPN: $protocol", denied)
                blocked.put(JSONObject().put("protocol", "ipv6-$protocol").put("request_sha256", ControlledDatapathProbe.sha(payload)).put("blocked", true))
            }
            // 只改变本次已确认启用的 Wi-Fi；USB 不受影响，finally 在任何失败路径恢复启用。
            wifiDisabled = true
            shell("svc wifi disable")
            withTimeout(15_000) { while (wifi() != null) delay(100) }
            withTimeout(10_000) { while (app.sessions.state.value.sessionId == initial.sessionId && app.sessions.state.value.phase == ConnectionPhase.Running) delay(100) }
            delay(2_000)
            shell("svc wifi enable")
            wifiDisabled = false
            withTimeout(30_000) { while (wifi() == null || wifi() == originalWifi) delay(100) }
            // Wi-Fi 的 IPv4、IPv6 和 DNS 分阶段发布会触发多次真实重连。
            // 观测平台参数及已确认会话同时稳定两秒后只发送一次业务请求，不靠重发获得成功。
            val recovered = withTimeout(60_000) {
                var stableSince = android.os.SystemClock.elapsedRealtime()
                var previousSession: SessionId? = null
                var previousProperties: android.net.LinkProperties? = null
                while (true) {
                    val state = app.sessions.state.value
                    check(state.phase !in setOf(ConnectionPhase.Failed, ConnectionPhase.OutcomeUnknown)) { "RECOVERY_NOT_CONFIRMED: ${state.phase}" }
                    val properties = wifi()?.let(manager::getLinkProperties)
                    val ready = state.phase == ConnectionPhase.Running && (state.sessionId?.generation ?: 0) > initial.sessionId!!.generation && state.core?.value?.peers?.any { p -> p.connections > 0 } == true
                    if (!ready || properties != previousProperties || state.sessionId != previousSession) stableSince = android.os.SystemClock.elapsedRealtime()
                    previousProperties = properties
                    previousSession = state.sessionId
                    if (ready && properties != null && android.os.SystemClock.elapsedRealtime() - stableSince >= 2_000) return@withTimeout state
                    delay(100)
                }
                error("UNREACHABLE")
            }
            business("recovered")
            assertEquals(0L, recovered.core!!.value!!.protectionFailures)
            report.put("initial_generation", initial.sessionId!!.generation).put("recovered_generation", recovered.sessionId!!.generation)
                .put("physical_wifi_identity_changed", true).put("protection_failures", recovered.core!!.value!!.protectionFailures)
            withContext(Dispatchers.Main) { app.sessions.disconnect() }
            await(app) { it.phase == ConnectionPhase.Idle }
            physical("after-vpn")
            val recoveredWifi = requireNotNull(wifi())
            wifiDisabled = true
            shell("svc wifi disable")
            withTimeout(15_000) { while (wifi() != null) delay(100) }
            assertEquals(ConnectionPhase.Idle, app.sessions.state.value.phase)
            shell("svc wifi enable")
            wifiDisabled = false
            withTimeout(30_000) { while (wifi() == null || wifi() == recoveredWifi) delay(100) }
            delay(2_000)
            assertEquals(ConnectionPhase.Idle, app.sessions.state.value.phase)
            val resources = withContext(Dispatchers.Main) { app.sessions.resources() }!!
            assertEquals(0, resources.activeSessions)
            assertEquals(0, resources.ownedTunDescriptors)
            report.put("manual_stop_survives_wifi_recovery", true).put("active_sessions_after_stop", 0).put("owned_pfds_after_stop", 0).put("status", "passed")
        } finally {
            if (wifiDisabled) shell("svc wifi enable")
            withContext(Dispatchers.Main) { app.sessions.disconnect() }
            try { await(app) { it.phase == ConnectionPhase.Idle } }
            finally {
                report.put("final_phase", app.sessions.state.value.phase.name)
                report.put("last_generation", app.sessions.state.value.sessionId?.generation)
                app.openFileOutput("p02-network-report.json", android.content.Context.MODE_PRIVATE).use { it.write(report.toString(2).toByteArray()) }
                activity?.close()
            }
        }
    }

    private fun ipv6Roundtrip(host: String, protocol: String, payload: ByteArray): ByteArray {
        val address = InetAddress.getByName(host)
        if (protocol == "tcp") return Socket().use { socket ->
            socket.connect(InetSocketAddress(address, 31275), 3_000)
            socket.soTimeout = 3_000
            socket.getOutputStream().write(payload)
            val response = ByteArray(payload.size)
            var offset = 0
            while (offset < response.size) {
                val count = socket.getInputStream().read(response, offset, response.size - offset)
                check(count > 0) { "IPV6_RESPONSE_TRUNCATED" }
                offset += count
            }
            response
        }
        return DatagramSocket().use { socket ->
            socket.soTimeout = 3_000
            socket.connect(address, 31276)
            socket.send(DatagramPacket(payload, payload.size))
            val response = DatagramPacket(ByteArray(4096), 4096)
            socket.receive(response)
            response.data.copyOf(response.length)
        }
    }

    private suspend fun await(app: ChuanmenApplication, predicate: (SessionState) -> Boolean): SessionState = withTimeout(60_000) {
        while (!predicate(app.sessions.state.value)) {
            check(app.sessions.state.value.phase !in setOf(ConnectionPhase.Failed, ConnectionPhase.OutcomeUnknown)) { "NETWORK_SESSION_NOT_CONFIRMED: ${app.sessions.state.value.phase}" }
            delay(100)
        }
        app.sessions.state.value
    }
}
