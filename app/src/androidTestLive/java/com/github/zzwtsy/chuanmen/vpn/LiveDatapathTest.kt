package com.github.zzwtsy.chuanmen.vpn

import android.net.VpnService
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.zzwtsy.chuanmen.MainActivity
import com.github.zzwtsy.chuanmen.application.LiveApplication
import com.github.zzwtsy.chuanmen.contracts.*
import com.github.zzwtsy.chuanmen.feature.configuration.controlled.ControlledProfileInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 正式 APK / Service 的受控业务回包；参数缺失直接失败，不能退化成模拟通过。 */
@RunWith(AndroidJUnit4::class)
class LiveDatapathTest {
    /** TCP、UDP 外层分别传输 TCP/UDP 业务；Activity 重建保留会话，断开等待真实清理。 */
    @Test fun controlledPeerRoundtripsThroughFormalService() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val host = requireNotNull(args.getString("peerHost")) { "CONTROLLED_PEER_MISSING" }
        require(host.split('.').let { parts -> parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } })
        val runId = requireNotNull(args.getString("runId")) { "RUN_ID_MISSING" }
        require(runId.startsWith("p02-"))
        val app = instrumentation.targetContext.applicationContext as LiveApplication
        val profile = LocalProfileId("00000000-0000-4000-8000-000000000202")
        val repository = ControlledProfileInput.create(profile, CoreInstanceId("p02-controlled-android"),
            "受控真机", "chuanmen-p02-controlled", NetworkSecret.of("p02-synthetic-public-test-only"),
            listOf(PeerEndpoint("tcp://$host:31270")), Ipv4Assignment.Static("10.222.0.2"), emptyList())
        withContext(Dispatchers.Main) { app.setRepository(repository) }
        val requests = JSONArray()
        val scenarios = JSONArray()
        val report = JSONObject().put("run_id", runId).put("status", "failed").put("requests", requests).put("scenarios", scenarios)
        var activity: ActivityScenario<MainActivity>? = null
        try {
            check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) { "DEVICE_LOCKED_BEFORE_ACTIVITY" }
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            for (outer in listOf("tcp", "udp")) {
                val previous = repository.findSaved(profile)!!
                val saved = if (outer == "tcp") previous else repository.save(profile,
                    previous.core.copy(peers = listOf(PeerEndpoint("udp://$host:31270"))), previous.vpnPolicy)!!
                val result = withContext(Dispatchers.Main) { app.sessions.connect(saved) }
                assertTrue("CONNECT_NOT_ACCEPTED: $result", result is OperationResult.Accepted)
                // 系统授权走真实 Activity 回调；人工确认的预算独立于核心启动预算。
                withTimeout(90_000) {
                    while (VpnService.prepare(app) != null) delay(250)
                }
                val running = await(app) { it.phase == ConnectionPhase.Running && it.core?.value?.peers?.any { p -> p.connections > 0 } == true }
                assertEquals(saved, running.applied!!.saved)
                assertEquals(DataFreshness.Fresh, running.core!!.freshness)
                assertTrue(running.core!!.value!!.protectionCalls > 0)
                assertEquals(0L, running.core!!.value!!.protectionFailures)
                val before = running.runtime?.value?.tunCounters?.value
                activity.recreate()
                activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                assertEquals(running.sessionId, app.sessions.state.value.sessionId)
                for (inner in listOf("tcp", "udp")) {
                    val payload = "$runId:$outer:$inner:${System.nanoTime()}\n".toByteArray(Charsets.UTF_8)
                    val received = withContext(Dispatchers.IO) { ControlledDatapathProbe.roundtrip(inner, payload) }
                    assertArrayEquals(payload, received)
                    requests.put(JSONObject().put("outer", outer).put("inner", inner).put("bytes", payload.size)
                        .put("request_sha256", ControlledDatapathProbe.sha(payload)).put("response_sha256", ControlledDatapathProbe.sha(received)))
                }
                val measured = await(app) {
                    val counters = it.runtime?.value?.tunCounters?.value
                    counters != null && counters.txBytes > (before?.txBytes ?: 0) && counters.rxBytes > (before?.rxBytes ?: 0)
                }
                scenarios.put(JSONObject().put("outer", outer).put("phase", measured.phase.name)
                    .put("generation", measured.sessionId!!.generation).put("revision", saved.revision)
                    .put("actual_ipv4", measured.applied!!.virtualIpv4.value)
                    .put("protection_calls", measured.core!!.value!!.protectionCalls)
                    .put("protection_failures", measured.core!!.value!!.protectionFailures)
                    .put("tx_bytes", measured.runtime!!.value!!.tunCounters.value!!.txBytes)
                    .put("rx_bytes", measured.runtime!!.value!!.tunCounters.value!!.rxBytes)
                    .put("activity_recreation_preserved_session", true))
                withContext(Dispatchers.Main) { app.sessions.disconnect() }
                await(app) { it.phase == ConnectionPhase.Idle }
                delay(1_200)
                assertEquals(ConnectionPhase.Idle, app.sessions.state.value.phase)
                assertNull(app.sessions.state.value.sessionId)
            }
            report.put("status", "passed")
        } finally {
            withContext(Dispatchers.Main) { app.sessions.disconnect() }
            try { await(app) { it.phase == ConnectionPhase.Idle } }
            finally {
                val relative = "p02-datapath-report.json"
                app.openFileOutput(relative, android.content.Context.MODE_PRIVATE).use { it.write(report.toString(2).toByteArray()) }
                instrumentation.sendStatus(0, Bundle().apply { putString("p02_report", relative) })
                activity?.close()
            }
        }
    }

    private suspend fun await(app: LiveApplication, predicate: (SessionState) -> Boolean): SessionState = withTimeout(45_000) {
        while (!predicate(app.sessions.state.value)) {
            val state = app.sessions.state.value
            check(state.phase != ConnectionPhase.Failed && state.phase != ConnectionPhase.OutcomeUnknown) { "SESSION_FAILED: ${state.phase}/${state.failure}" }
            delay(100)
        }
        app.sessions.state.value
    }

}
