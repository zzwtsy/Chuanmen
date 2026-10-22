package com.github.zzwtsy.chuanmen.lib.vpn

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.zzwtsy.chuanmen.ChuanmenApplication
import com.github.zzwtsy.chuanmen.MainActivity
import com.github.zzwtsy.chuanmen.features.configuration.lib.ConfigurationInput
import com.github.zzwtsy.chuanmen.lib.profile.CoreInstanceId
import com.github.zzwtsy.chuanmen.lib.profile.Ipv4Assignment
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.NetworkSecret
import com.github.zzwtsy.chuanmen.lib.profile.PeerEndpoint
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 正常 Android resolver 查询受控虚拟 DNS；不能用原始 UDP 包或 DNS 配置读回替代。 */
@RunWith(AndroidJUnit4::class)
class LiveDnsTest {
    /** 双外层分别解析本轮唯一域名，核对实际答案、VPN DNS 与成功 TUN I/O，最后确认停止。 */
    @Test fun controlledResolverQueriesHaveActualPeerAnswers() = runBlocking<Unit> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val host = requireNotNull(arguments.getString("peerHost"))
        val runId = requireNotNull(arguments.getString("runId"))
        require(runId.matches(Regex("p02-[A-Za-z0-9-]{1,35}")))
        val app = instrumentation.targetContext.applicationContext as ChuanmenApplication
        val id = LocalProfileId("00000000-0000-4000-8000-000000000253")
        val repository = ConfigurationInput.create(id, CoreInstanceId("p02-dns-android"), "DNS 回归",
            "chuanmen-p02-controlled", NetworkSecret.of("p02-synthetic-public-test-only"),
            listOf(PeerEndpoint("tcp://$host:31270")), Ipv4Assignment.Static("10.222.0.2"), listOf("10.222.0.1"))
        withContext(Dispatchers.Main) { app.setRepository(repository) }
        val queries = JSONArray()
        val report = JSONObject().put("run_id", runId).put("status", "failed").put("queries", queries)
        var activity: ActivityScenario<MainActivity>? = null
        try {
            check(!app.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            activity = ActivityScenario.launch(MainActivity::class.java)
            activity.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            for (outer in listOf("tcp", "udp")) {
                val old = repository.findSaved(id)!!
                val saved = if (outer == "tcp") old else repository.save(id,
                    old.core.copy(peers = listOf(PeerEndpoint("udp://$host:31270"))), old.vpnPolicy)!!
                assertTrue(withContext(Dispatchers.Main) { app.sessions.connect(saved) } is OperationResult.Accepted)
                withTimeout(90_000) { while (VpnService.prepare(app) != null) delay(250) }
                await(app) { it.phase == ConnectionPhase.Running && it.core?.value?.peers?.any { peer -> peer.connections > 0 } == true }
                val manager = app.getSystemService(ConnectivityManager::class.java)
                val network = withTimeout(10_000) {
                    var candidate: android.net.Network?
                    do {
                        candidate = manager.allNetworks.singleOrNull {
                            manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                        }
                        if (candidate == null) delay(100)
                    } while (candidate == null)
                    candidate
                }
                assertEquals(listOf("10.222.0.1"), manager.getLinkProperties(network)!!.dnsServers.map { it.hostAddress })
                val name = "${runId.lowercase()}-${java.lang.Long.toUnsignedString(System.nanoTime(), 16)}.p02.invalid"
                val answers = withContext(Dispatchers.IO) { network.getAllByName(name).map { requireNotNull(it.hostAddress) }.sorted() }
                assertEquals(listOf("10.222.0.1"), answers)
                val measured = await(app) { (it.core?.value?.counters?.txBytes ?: 0) > 0 && (it.core?.value?.counters?.rxBytes ?: 0) > 0 }
                assertEquals(0L, measured.core!!.value!!.protectionFailures)
                queries.put(JSONObject().put("outer", outer).put("query_name_sha256", ControlledDatapathProbe.sha(name.toByteArray()))
                    .put("answers", JSONArray(answers)).put("vpn_network", true).put("dns_servers", JSONArray(listOf("10.222.0.1")))
                    .put("actual_ipv4", measured.applied!!.virtualIpv4.value).put("generation", measured.sessionId!!.generation)
                    .put("tx_bytes", measured.core!!.value!!.counters!!.txBytes).put("rx_bytes", measured.core!!.value!!.counters!!.rxBytes)
                    .put("protection_failures", measured.core!!.value!!.protectionFailures))
                withContext(Dispatchers.Main) { app.sessions.disconnect() }
                await(app) { it.phase == ConnectionPhase.Idle }
            }
            report.put("status", "passed")
        } finally {
            withContext(Dispatchers.Main) { app.sessions.disconnect() }
            try { await(app) { it.phase == ConnectionPhase.Idle } }
            finally {
                app.openFileOutput("p02-dns-report.json", android.content.Context.MODE_PRIVATE).use { it.write(report.toString(2).toByteArray()) }
                activity?.close()
            }
        }
    }

    private suspend fun await(app: ChuanmenApplication, predicate: (SessionState) -> Boolean): SessionState = withTimeout(45_000) {
        while (!predicate(app.sessions.state.value)) {
            val state = app.sessions.state.value
            check(state.phase !in setOf(ConnectionPhase.Failed, ConnectionPhase.OutcomeUnknown)) { "DNS_SESSION_NOT_CONFIRMED: ${state.phase}/${state.failure}" }
            delay(100)
        }
        app.sessions.state.value
    }
}
