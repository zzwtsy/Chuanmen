package com.github.zzwtsy.chuanmen.lib.vpn

import android.net.VpnService
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
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 正式 Service 的资源回归；先双外层预热，再连续执行 50 轮，未确认停止立即失败。 */
@RunWith(AndroidJUnit4::class)
class LiveResourceRegressionTest {
    /** 每轮均验证真实回包、停止后的无实例/借用/回调/JNI 引用，以及固定资源基线。 */
    @Test fun fiftySessionsReleaseOwnedResourcesAfterWarmup() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val host = requireNotNull(arguments.getString("peerHost")) { "CONTROLLED_PEER_MISSING" }
        require(host.split('.').let { p -> p.size == 4 && p.all { it.toIntOrNull() in 0..255 } })
        val runId = requireNotNull(arguments.getString("runId")) { "RUN_ID_MISSING" }
        require(runId.startsWith("p02-"))
        val app = instrumentation.targetContext.applicationContext as ChuanmenApplication
        val repository = app.profiles
        repository.load()
        val creation = controlledInput(CoreInstanceId("p02-resources-android"), "chuanmen-p02-controlled", NetworkSecret.of("p02-synthetic-public-test-only"), listOf(PeerEndpoint("tcp://$host:31270")), Ipv4Assignment.Static("10.222.0.2"), emptyList())
        val initialProfile = repository.create(creation.first.controlledDocument(), creation.second, "资源回归").requireSuccess()
        val id = initialProfile.profileId
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val observer = NativeCoreEngine(observerScope)
        val cycles = JSONArray()
        val requests = JSONArray()
        val report = JSONObject().put("run_id", runId).put("status", "failed")
            .put("warmup_cycles", 4).put("required_cycles", 50).put("cycles", cycles).put("requests", requests)
        var activity: ActivityScenario<MainActivity>? = null
        var baselineFds: Int? = null
        var baselineThreads: Int? = null
        var previousGeneration = 0L
        try {
            check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) { "DEVICE_LOCKED_BEFORE_ACTIVITY" }
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity { it.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            withTimeout(900_000) {
                repeat(54) { index ->
                    val outer = if (index % 2 == 0) "tcp" else "udp"
                    val old = repository.findSaved(id)!!
                    val saved = if (index == 0) old else repository.save(id, old.core.copy(peers = listOf(PeerEndpoint("$outer://$host:31270"))).controlledDocument(), old.vpnPolicy, old.revision).requireSuccess()
                    assertTrue(withContext(Dispatchers.Main) { app.sessions.connect(saved) } is OperationResult.Accepted)
                    withTimeout(90_000) { while (VpnService.prepare(app) != null) delay(250) }
                    val running = await(app) { it.phase == ConnectionPhase.Running && it.core?.value?.peers?.any { p -> p.connections > 0 } == true }
                    val session = requireNotNull(running.sessionId)
                    assertTrue(session.generation > previousGeneration)
                    previousGeneration = session.generation
                    for (inner in listOf("tcp", "udp")) {
                        val payload = "$runId:$index:$outer:$inner:${System.nanoTime()}\n".toByteArray()
                        val response = withContext(Dispatchers.IO) { ControlledDatapathProbe.roundtrip(inner, payload) }
                        assertArrayEquals(payload, response)
                        requests.put(JSONObject().put("cycle", index).put("outer", outer).put("inner", inner)
                            .put("request_sha256", ControlledDatapathProbe.sha(payload)).put("response_sha256", ControlledDatapathProbe.sha(response)).put("bytes", payload.size))
                    }
                    val actual = await(app) { (it.core?.value?.counters?.txBytes ?: 0) > 0 && (it.core?.value?.counters?.rxBytes ?: 0) > 0 }
                    assertEquals(0L, actual.core!!.value!!.protectionFailures)
                    withContext(Dispatchers.Main) { app.sessions.disconnect() }
                    await(app) { it.phase == ConnectionPhase.Idle }
                    delay(300)
                    val resources = withTimeout(3_000) {
                        var resource: ServiceResourceCounts
                        do {
                            resource = withContext(Dispatchers.Main) { requireNotNull(app.sessions.resources()) }
                            if (resource.scopeJobs != 1) delay(50)
                        } while (resource.scopeJobs != 1)
                        resource
                    }
                    assertEquals(0, resources.activeSessions)
                    assertEquals(0, resources.ownedTunDescriptors)
                    assertEquals(1L, resources.lifecycleSubscribers)
                    val query = OperationToken(session, 10_000 + index.toLong())
                    observer.submit(EngineCommand.QueryRuntime(query))
                    val stopped = withTimeout(10_000) { observer.events.first { it.token == query } }
                    assertTrue(stopped is EngineEvent.CoreObserved)
                    val core = (stopped as EngineEvent.CoreObserved).snapshot
                    assertEquals(RuntimeStatus.Stopped, core.status)
                    assertEquals(0L, core.activeInstances)
                    assertNull(core.attachmentId)
                    assertNull(core.attachmentState)
                    assertEquals(0L, core.callbacksInFlight)
                    assertEquals(0L, core.jniGlobalRefs)
                    assertEquals(0L, observer.lifecycleSubscriberCount)
                    val fds = requireNotNull(File("/proc/self/fd").list()).size
                    val workers = requireNotNull(core.runtimeThreads) { "SHARED_RUNTIME_WORKERS_UNAVAILABLE" }.toInt()
                    assertTrue("SHARED_RUNTIME_WORKERS_UNAVAILABLE", workers >= 2)
                    if (index == 3) { baselineFds = fds; baselineThreads = workers }
                    if (index >= 4) {
                        assertTrue("FD_BASELINE_GROWTH: $fds / $baselineFds", fds <= requireNotNull(baselineFds) + 2)
                        assertEquals(requireNotNull(baselineThreads), workers)
                    }
                    // 本机排空不等于远端 UDP 探活完成；逐轮核对旧连接及路由，保留实际收束时长。
                    val settlingStarted = android.os.SystemClock.elapsedRealtime()
                    val peerStopped = withTimeout(45_000) {
                        var settled = 0
                        var peer: JSONObject
                        do {
                            peer = withContext(Dispatchers.IO) { ControlledDatapathProbe.peerStatus(host, runId) }
                            settled = if (peer.getInt("active_connections") == 0 && peer.getInt("reachable_routes") == 0) settled + 1 else 0
                            if (settled < 3) delay(250)
                        } while (settled < 3)
                        peer
                    }
                    cycles.put(JSONObject().put("index", index).put("warmup", index < 4).put("outer", outer)
                        .put("generation", session.generation).put("fd_count", fds).put("shared_runtime_workers", workers)
                        .put("active_instances", core.activeInstances).put("tun_borrows", 0).put("callbacks_in_flight", core.callbacksInFlight)
                        .put("jni_global_refs", core.jniGlobalRefs).put("owned_pfds", resources.ownedTunDescriptors)
                        .put("service_lifecycle_subscribers", resources.lifecycleSubscribers).put("service_jobs", resources.scopeJobs)
                        .put("observer_subscribers", observer.lifecycleSubscriberCount).put("phase_after_stop", app.sessions.state.value.phase.name)
                        .put("tx_bytes", actual.core!!.value!!.counters!!.txBytes).put("rx_bytes", actual.core!!.value!!.counters!!.rxBytes)
                        .put("protection_calls", actual.core!!.value!!.protectionCalls).put("protection_failures", actual.core!!.value!!.protectionFailures)
                        .put("peer_connections_after_stop", peerStopped.getInt("active_connections"))
                        .put("peer_routes_after_stop", peerStopped.getInt("reachable_routes"))
                        .put("peer_settle_millis", android.os.SystemClock.elapsedRealtime() - settlingStarted))
                }
            }
            report.put("baseline_fds", baselineFds).put("baseline_shared_runtime_workers", baselineThreads).put("status", "passed")
        } finally {
            withContext(Dispatchers.Main) { app.sessions.disconnect() }
            try { await(app) { it.phase == ConnectionPhase.Idle } }
            finally {
                observerScope.cancel()
                app.openFileOutput("p02-resource-report.json", android.content.Context.MODE_PRIVATE).use { it.write(report.toString(2).toByteArray()) }
                activity?.close()
            }
        }
    }

    private suspend fun await(app: ChuanmenApplication, predicate: (SessionState) -> Boolean): SessionState = withTimeout(45_000) {
        while (!predicate(app.sessions.state.value)) {
            val state = app.sessions.state.value
            check(state.phase !in setOf(ConnectionPhase.Failed, ConnectionPhase.OutcomeUnknown)) { "SESSION_NOT_CONFIRMED: ${state.phase}/${state.failure}" }
            delay(100)
        }
        app.sessions.state.value
    }
}
