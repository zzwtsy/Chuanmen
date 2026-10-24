package com.github.zzwtsy.chuanmen.lib.vpn

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class ObservabilityContractTest {
    private val token = OperationToken(SessionId("synthetic-observation", 1), 3)
    private fun result(): JsonObject = JsonParser.parseString("""{
        "phase":"running","core_state":"running","saved_revision":1,"last_operation":3,
        "ipv4":"10.42.0.1/24","peers":[{"peer_id":2,"connections":1,"network_role":"member","links":[{
            "connection_id":"synthetic-link","tx_bytes":"18446744073709551615","rx_bytes":"0",
            "tx_packets":"9007199254740993","rx_packets":"1","latency_us":"1200","latency_valid":true}]}],
        "routes":[{"peer_id":2,"ipv4":"10.42.0.2/24","proxy_cidrs":[],"cost":1,"version":"2.7.0",
            "hostname":"synthetic-peer","next_hop_peer_id":2,"udp_nat_type":123,"tcp_nat_type":null}],
        "udp_nat_type":123,"tcp_nat_type":null,"attachment":null,"attachment_phase":null,
        "tun_counters":{"tx_bytes":"9007199254740993","rx_bytes":"0","tx_packets":"1","rx_packets":"0"},
        "active_instances":1,"callbacks_in_flight":0,"jni_global_refs":1,"protection_calls":1,"protection_failures":0,
        "capabilities":["observability-v1","observability-v2"],"local_peer_id":1,"local_hostname":"synthetic-local","local_version":"2.7.0"
    }""").asJsonObject
    private fun decode(result: JsonObject) = BridgeProtocolCodec.snapshot(JsonObject().apply { add("result", result) }, token)

    /** 桥明确声明 RTT 有效性；未采样与实测零不同，旧桥无能力时不推测 RTT 或邻居角色。 */
    @Test fun latencyValidityAndMembershipAreCapabilityGated() {
        val result = result()
        val peer = result.getAsJsonArray("peers")[0].asJsonObject
        val link = peer.getAsJsonArray("links")[0].asJsonObject
        assertEquals(PeerNetworkRole.Member, decode(result).peers.single().networkRole)
        assertEquals(BigInteger("1200"), decode(result).peers.single().links.single().latencyMicros.value)
        link.addProperty("latency_us", "0")
        link.addProperty("latency_valid", false)
        assertEquals(MetricUnavailable.Missing, decode(result).peers.single().links.single().latencyMicros.unavailable)
        link.addProperty("latency_valid", true)
        assertEquals(BigInteger.ZERO, decode(result).peers.single().links.single().latencyMicros.value)
        link.addProperty("latency_valid", "synthetic-invalid")
        assertEquals(MetricUnavailable.Invalid, decode(result).peers.single().links.single().latencyMicros.unavailable)
        assertEquals(BigInteger.ZERO, decode(result).peers.single().links.single().rxBytes.value)
        result.add("capabilities", JsonParser.parseString("[\"observability-v1\"]"))
        val legacy = decode(result).peers.single()
        assertEquals(PeerNetworkRole.Unknown, legacy.networkRole)
        assertEquals(MetricUnavailable.Unsupported, legacy.links.single().latencyMicros.unavailable)
    }

    /** 新桥用十进制字符串传递 u64，最大值及超过 Double 精度的值均保留整数位。 */
    @Test fun unsignedCountersAreLosslessAndScopedToOneLink() {
        val snapshot = decode(result())
        val link = snapshot.peers.single().links.single()
        assertEquals(BigInteger("18446744073709551615"), link.txBytes.value)
        assertEquals(BigInteger("9007199254740993"), link.txPackets.value)
        assertEquals(BigInteger.ZERO, link.rxBytes.value)
        assertEquals(9_007_199_254_740_993L, snapshot.counters!!.txBytes)
        assertEquals(123, snapshot.routes.single().udpNatType.value)
        assertEquals(MetricUnavailable.Missing, snapshot.routes.single().tcpNatType.unavailable)
        assertNull(snapshot.tcpNatType)
    }

    /** TUN 计数超过 Long 范围时明确不可表示，其他设备指标仍有效且不变成负数。 */
    @Test fun tunOverflowDoesNotDiscardOtherMetrics() {
        val result = result()
        result.getAsJsonObject("tun_counters").addProperty("tx_bytes", "18446744073709551615")
        val snapshot = decode(result)
        assertNull(snapshot.counters)
        assertEquals(MetricUnavailable.OutOfRange, snapshot.countersUnavailable)
        assertEquals("synthetic-peer", snapshot.routes.single().hostname.value)
    }

    /** 非规范、超范围或缺少的单个链路指标各自不可用，不清掉相邻的有效字段。 */
    @Test fun malformedMetricsRemainLocalFailures() {
        for (value in listOf("-1", "1e3", "1.0", "01", "18446744073709551616")) {
            val result = result()
            result.getAsJsonArray("peers")[0].asJsonObject.getAsJsonArray("links")[0].asJsonObject.addProperty("tx_bytes", value)
            val link = decode(result).peers.single().links.single()
            assertNull(value, link.txBytes.value)
            assertNotNull(value, link.txBytes.unavailable)
            assertEquals(BigInteger.ZERO, link.rxBytes.value)
        }
    }

    /** 未声明能力时忽略新字段；旧桥仍可读取整数 TUN 计数，新指标明确不支持。 */
    @Test fun legacyBridgeCannotAccidentallyAdvertiseNewMetrics() {
        val result = result().apply { remove("capabilities"); remove("tun_counters") }
        val snapshot = decode(result)
        assertEquals(MetricUnavailable.Unsupported, snapshot.routes.single().hostname.unavailable)
        assertEquals(MetricUnavailable.Unsupported, snapshot.localVersion.unavailable)
        assertNull(snapshot.localPeerId)
        assertTrue(snapshot.peers.single().links.isEmpty())
    }

    /** 未知借用枚举保留为 Unknown，整包仍可读取；借用会话不匹配则拒绝整包。 */
    @Test fun unknownBorrowStateDoesNotCrashButWrongOwnerIsRejected() {
        val result = result()
        result.add("attachment", JsonParser.parseString("""{"session_id":"synthetic-observation","generation":1,"attachment":2}"""))
        result.addProperty("attachment_phase", "future-state")
        assertEquals(TunBorrowState.Unknown, decode(result).attachmentState)
        result.getAsJsonObject("attachment").addProperty("generation", 2)
        assertThrows(IllegalArgumentException::class.java) { decode(result) }
    }
}
