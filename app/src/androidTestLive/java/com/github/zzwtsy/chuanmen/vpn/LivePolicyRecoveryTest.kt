package com.github.zzwtsy.chuanmen.vpn

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.view.WindowManager
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.zzwtsy.chuanmen.MainActivity
import com.github.zzwtsy.chuanmen.application.*
import com.github.zzwtsy.chuanmen.contracts.*
import com.github.zzwtsy.chuanmen.feature.configuration.controlled.ControlledProfileInput
import com.github.zzwtsy.chuanmen.feature.configuration.edit.DraftField
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 真实 DHCP、默认路由和保存/运行恢复边界；仍采用单活动 VPN 的完整重连。 */
@RunWith(AndroidJUnit4::class)
class LivePolicyRecoveryTest {
    /** 实际自动地址承载回包；应用默认路由及 MTU 后再次回包，失败与恢复均保留保存、名称和草稿。 */
    @Test fun dhcpDefaultRouteAndExplicitRecoveryPreserveLocalEdits() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val host = requireNotNull(arguments.getString("peerHost"))
        val runId = requireNotNull(arguments.getString("runId"))
        require(runId.startsWith("p02-"))
        val app = instrumentation.targetContext.applicationContext as LiveApplication
        val id = LocalProfileId("00000000-0000-4000-8000-000000000254")
        val repository = ControlledProfileInput.create(id, CoreInstanceId("p02-policy-android"), "策略回归",
            "chuanmen-p02-controlled", NetworkSecret.of("p02-synthetic-public-test-only"),
            listOf(PeerEndpoint("tcp://$host:31270")), Ipv4Assignment.Automatic, emptyList())
        withContext(Dispatchers.Main) { app.setRepository(repository) }
        val requests = JSONArray()
        val steps = JSONArray()
        val report = JSONObject().put("run_id", runId).put("status", "failed").put("steps", steps).put("requests", requests)
        var activity: ActivityScenario<MainActivity>? = null
        var observing: Job? = null
        try {
            check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            activity = ActivityScenario.launch(MainActivity::class.java)
            lateinit var model: ChuanmenViewModel
            activity.onActivity {
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                model = ViewModelProvider(it, AppAssembly.factory)[ChuanmenViewModel::class.java]
            }
            observing = launch { model.state.collect { } }
            val initial = repository.findSaved(id)!!
            assertTrue(withContext(Dispatchers.Main) { app.sessions.connect(initial) } is OperationResult.Accepted)
            withTimeout(90_000) { while (VpnService.prepare(app) != null) delay(250) }
            val dhcp = await(app) { it.phase == ConnectionPhase.Running && it.core?.value?.peers?.any { peer -> peer.connections > 0 } == true }
            assertEquals(Ipv4Assignment.Automatic, dhcp.applied!!.saved.core.ipv4)
            assertEquals(dhcp.core!!.value!!.ipv4!!.address, dhcp.applied!!.virtualIpv4.value)
            assertNotEquals("0.0.0.0", dhcp.applied!!.virtualIpv4.value)
            assertNotEquals("10.222.0.1", dhcp.applied!!.virtualIpv4.value)
            exchange(runId, "dhcp", requests)
            steps.put(JSONObject().put("step", "dhcp").put("actual_ipv4", dhcp.applied!!.virtualIpv4.value)
                .put("generation", dhcp.sessionId!!.generation).put("revision", initial.revision))

            val target = repository.save(id, initial.core.copy(mtu = 1400), initial.vpnPolicy.copy(includeInternet = true))!!
            assertEquals(initial.revision, app.sessions.state.value.applied!!.saved.revision)
            assertTrue(withContext(Dispatchers.Main) { app.sessions.apply(target) } is OperationResult.Accepted)
            val applied = await(app) { it.phase == ConnectionPhase.Running && it.applied?.saved == target && it.runtime?.value?.interfaceSpec?.value != null && it.core?.value?.peers?.any { peer -> peer.connections > 0 } == true }
            assertTrue(applied.sessionId!!.generation > dhcp.sessionId!!.generation)
            val spec = applied.runtime!!.value!!.interfaceSpec.value!!
            assertEquals(1400, spec.mtu)
            assertTrue(Ipv4Cidr("0.0.0.0", 0) in spec.routes)
            val manager = app.getSystemService(ConnectivityManager::class.java)
            val vpn = manager.allNetworks.single { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            val platform = manager.getLinkProperties(vpn)!!
            assertEquals(1400, platform.mtu)
            assertTrue(platform.routes.any { it.destination.toString() == "0.0.0.0/0" })
            exchange(runId, "default-route", requests)
            val measured = await(app) { (it.core?.value?.counters?.txBytes ?: 0) > 0 && (it.core?.value?.counters?.rxBytes ?: 0) > 0 }
            assertEquals(0L, measured.core!!.value!!.protectionFailures)
            steps.put(JSONObject().put("step", "default-route").put("actual_ipv4", measured.applied!!.virtualIpv4.value)
                .put("generation", measured.sessionId!!.generation).put("revision", target.revision).put("mtu", platform.mtu)
                .put("platform_default_route", true).put("protection_failures", measured.core!!.value!!.protectionFailures))

            val invalid = repository.save(id, target.core.copy(ipv4 = Ipv4Assignment.Static("0.0.0.0")), target.vpnPolicy)!!
            withContext(Dispatchers.Main) {
                model.act(AppAction.Rename("保留的本地名称"))
                model.act(AppAction.Edit(DraftField.Mtu, "1450"))
            }
            withTimeout(5_000) { while (model.state.value.draft?.rawMtu != "1450") delay(50) }
            val draft = model.state.value.draft
            assertTrue(withContext(Dispatchers.Main) { app.sessions.apply(invalid) } is OperationResult.Accepted)
            val failed = await(app, allowFailure = true) { it.failure?.kind == OperationKind.Apply && it.phase in setOf(ConnectionPhase.Running, ConnectionPhase.Failed) }
            assertEquals(target, failed.applied!!.saved)
            if (failed.phase == ConnectionPhase.Failed) {
                assertEquals(DataFreshness.Stale, failed.applied!!.freshness)
                assertEquals(0, withContext(Dispatchers.Main) { app.sessions.resources()!!.activeSessions })
                assertEquals(0, withContext(Dispatchers.Main) { app.sessions.resources()!!.ownedTunDescriptors })
            } else {
                assertEquals(DataFreshness.Fresh, failed.applied!!.freshness)
                assertEquals(applied.sessionId, failed.sessionId)
            }
            steps.put(JSONObject().put("step", "failed-apply").put("actual_phase", failed.phase.name)
                .put("failure_reason", failed.failure!!.reason.name).put("historical_revision", target.revision)
                .put("saved_revision", invalid.revision))
            val recovery = withContext(Dispatchers.Main) { app.sessions.restoreRunning() }
            assertTrue(recovery is OperationResult.Accepted || recovery == OperationResult.Unchanged)
            val restored = await(app) { it.phase == ConnectionPhase.Running && it.applied?.saved == target && it.core?.value?.peers?.any { peer -> peer.connections > 0 } == true }
            exchange(runId, "restore", requests)
            assertEquals(invalid, repository.findSaved(id))
            assertEquals("保留的本地名称", repository.profiles.value.single().displayName)
            assertEquals(draft, model.state.value.draft)
            steps.put(JSONObject().put("step", "restore").put("generation", restored.sessionId!!.generation)
                .put("running_revision", restored.applied!!.saved.revision).put("saved_revision", invalid.revision)
                .put("saved_preserved", true).put("name_preserved", true).put("draft_preserved", true))
            withContext(Dispatchers.Main) { app.sessions.disconnect() }
            await(app) { it.phase == ConnectionPhase.Idle }
            delay(1_200)
            assertEquals(ConnectionPhase.Idle, app.sessions.state.value.phase)
            report.put("status", "passed")
        } finally {
            withContext(Dispatchers.Main) { app.sessions.disconnect() }
            try { await(app) { it.phase == ConnectionPhase.Idle } }
            finally {
                observing?.cancel()
                app.openFileOutput("p02-policy-report.json", android.content.Context.MODE_PRIVATE).use { it.write(report.toString(2).toByteArray()) }
                activity?.close()
            }
        }
    }

    private suspend fun exchange(runId: String, step: String, requests: JSONArray) {
        for (inner in listOf("tcp", "udp")) {
            val payload = "$runId:$step:$inner:${System.nanoTime()}\n".toByteArray()
            val response = withContext(Dispatchers.IO) { ControlledDatapathProbe.roundtrip(inner, payload) }
            assertArrayEquals(payload, response)
            requests.put(JSONObject().put("step", step).put("inner", inner).put("bytes", payload.size)
                .put("request_sha256", ControlledDatapathProbe.sha(payload)).put("response_sha256", ControlledDatapathProbe.sha(response)))
        }
    }

    private suspend fun await(app: LiveApplication, allowFailure: Boolean = false, predicate: (SessionState) -> Boolean): SessionState = withTimeout(45_000) {
        while (!predicate(app.sessions.state.value)) {
            val state = app.sessions.state.value
            check(state.phase != ConnectionPhase.OutcomeUnknown && (allowFailure || state.phase != ConnectionPhase.Failed)) { "POLICY_SESSION_NOT_CONFIRMED: ${state.phase}/${state.failure}" }
            delay(100)
        }
        app.sessions.state.value
    }
}
