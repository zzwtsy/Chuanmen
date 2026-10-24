package com.github.zzwtsy.chuanmen.lib.profile

import com.github.zzwtsy.chuanmen.lib.vpn.AppliedSessionSnapshot
import com.github.zzwtsy.chuanmen.lib.vpn.DataFreshness
import com.github.zzwtsy.chuanmen.lib.vpn.EngineCommand
import com.github.zzwtsy.chuanmen.lib.vpn.Observation
import com.github.zzwtsy.chuanmen.lib.vpn.OperationToken
import com.github.zzwtsy.chuanmen.lib.vpn.SessionId
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest

class ProfileContractTest {
    private val profile = LocalProfileId("00000000-0000-4000-8000-000000000021")

    /** 合成密码嵌入配置、保存修订、命令和运行快照时，字符串输出都不能包含秘密原文。 */
    @Test
    fun nestedSecret_isRedacted() = runTest {
        val secret = NetworkSecret.of("synthetic-p02-test-value")
        val core = CoreConfig(CoreInstanceId("contract-instance"), "contract-network", null, networkSecret = secret)
        val saved = SavedRevision(profile, 1, core, AndroidVpnPolicy())
        val session = SessionId("contract-session", 1)
        val command = EngineCommand.Start(OperationToken(session, 1), saved)
        val applied = AppliedSessionSnapshot(session, saved, Observation("10.42.0.1", 0, DataFreshness.Fresh))
        listOf(secret, core, saved, command, applied).forEach {
            assertFalse(it.toString().contains(secret.revealForCore()))
            assertTrue(it.toString().contains("<redacted>"))
        }
        assertEquals(NetworkSecret.of("synthetic-p02-test-value"), secret)
        assertNotEquals(NetworkSecret.of("another-synthetic-value"), secret)
    }

    /** TCP 与 UDP URI 均保留协议、地址和端口，不解析 DNS、不自动切换外层协议。 */
    @Test
    fun controlledPeers_keepTransport() = runTest {
        assertEquals("tcp://192.0.2.10:11010", PeerEndpoint("tcp://192.0.2.10:11010").value)
        assertEquals("udp://test.invalid:11010", PeerEndpoint("udp://test.invalid:11010").value)
    }

    /** 含凭据、查询、非法端口或非 P02 协议的 URI 被拒绝，错误消息不回显敏感输入。 */
    @Test
    fun unsupportedOrSecretBearingPeers_areRejectedWithoutEcho() = runTest {
        listOf("tcp://user:synthetic-value@host:11010", "udp://host:11010?password=synthetic-value",
            "tcp://host:11010#synthetic-value", "tcp://host:0", "tcp://host:65536", "wg://host:11010",
            "tcp://host:11010/path", "invalid synthetic-value").forEach {
            val failure = assertThrows(IllegalArgumentException::class.java) { PeerEndpoint(it) }
            assertFalse(failure.message.orEmpty().contains(it))
            assertFalse(failure.message.orEmpty().contains("synthetic-value"))
        }
    }

    /** 静态 IPv4 与受控 DNS 只接受规范 IPv4 字面量，拒绝溢出、前导零与宿主名称。 */
    @Test
    fun addressValidation_doesNotAcceptHostnamesOrMalformedLiterals() = runTest {
        Ipv4Assignment.Static("10.42.0.9")
        AndroidVpnPolicy(dnsServers = listOf("10.42.0.53"))
        listOf("10.42.0.256", "10.42.0", "010.42.0.9", "test.invalid", "::1", " 10.42.0.9").forEach {
            assertThrows(IllegalArgumentException::class.java) { Ipv4Assignment.Static(it) }
            assertThrows(IllegalArgumentException::class.java) { AndroidVpnPolicy(dnsServers = listOf(it)) }
        }
    }

    /** 未提供密码和对端的模型输入仍合法；平台 IPv6 策略明确为 Block。 */
    @Test
    fun optionalConnectionInput_doesNotClaimConnectivity() = runTest {
        val core = CoreConfig(CoreInstanceId("synthetic-instance"), "synthetic-network", null)
        assertNull(core.networkSecret)
        assertTrue(core.peers.isEmpty())
        assertEquals(Ipv6Policy.Block, AndroidVpnPolicy().ipv6)
    }

    /** 初始名称与后续改名采用相同规范化：空白回退网络名，非空去首尾空白，保存身份不变。 */
    @Test fun initialNameNormalization_matchesRename() = runTest {
        val saved = SavedRevision(profile, 1, CoreConfig(CoreInstanceId("name-contract"), "network", null), AndroidVpnPolicy())
        listOf("", "  ", " 本地名称 ").forEach { name ->
            val repository = MemoryProfileRepository(listOf(ProfileSummary(saved, name)))
            val initial = repository.profiles.value.single().displayName
            assertEquals(if (name.isBlank()) "network" else "本地名称", initial)
            assertEquals(WriteResult.Success(Unit), repository.rename(profile, name))
            assertEquals(initial, repository.profiles.value.single().displayName)
            assertEquals(saved, repository.findSaved(profile))
        }
    }
}
