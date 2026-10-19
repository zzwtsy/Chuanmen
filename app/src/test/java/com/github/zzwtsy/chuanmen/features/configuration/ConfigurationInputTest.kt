package com.github.zzwtsy.chuanmen.features.configuration

import com.github.zzwtsy.chuanmen.features.configuration.lib.ConfigurationInput
import com.github.zzwtsy.chuanmen.lib.profile.CoreInstanceId
import com.github.zzwtsy.chuanmen.lib.profile.Ipv4Assignment
import com.github.zzwtsy.chuanmen.lib.profile.Ipv6Policy
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.NetworkSecret
import com.github.zzwtsy.chuanmen.lib.profile.PeerEndpoint
import org.junit.Assert.*
import org.junit.Test

class ConfigurationInputTest {
    private val profileId = LocalProfileId("00000000-0000-4000-8000-000000000022")
    private val instanceId = CoreInstanceId("controlled-instance")

    private fun create(peers: List<PeerEndpoint>, ipv4: Ipv4Assignment) = ConfigurationInput.create(
        profileId, instanceId, "受控网络", "controlled-network", NetworkSecret.of("synthetic-controlled-value"),
        peers, ipv4, listOf("10.42.0.53"),
    )

    /** TCP/UDP 对端与静态/DHCP 四种组合都进入已保存输入；本地名称不参与核心身份。 */
    @Test
    fun transportAndAddressMatrix_isSavedWithoutStartingSession() {
        listOf("tcp", "udp").forEach { transport ->
            listOf(Ipv4Assignment.Automatic, Ipv4Assignment.Static("10.42.0.9")).forEach { address ->
                val peer = PeerEndpoint("$transport://192.0.2.10:11010")
                val repository = create(listOf(peer), address)
                val saved = repository.findSaved(profileId)!!
                assertEquals(profileId, saved.profileId)
                assertEquals(instanceId, saved.core.instanceId)
                assertEquals(address, saved.core.ipv4)
                assertEquals(listOf(peer), saved.core.peers)
                assertEquals(listOf("10.42.0.53"), saved.vpnPolicy.dnsServers)
                assertEquals(Ipv6Policy.Block, saved.vpnPolicy.ipv6)
                assertEquals("受控网络", repository.profiles.value.single().displayName)
            }
        }
    }

    /** 受控输入未提供对端时拒绝创建仓库，避免把缺少测试前置解释为可连接。 */
    @Test
    fun missingPeer_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { create(emptyList(), Ipv4Assignment.Automatic) }
    }

    /** 改本地名称后核心与修订不变；保存 MTU 只增加修订，不覆盖名称或实例身份。 */
    @Test
    fun renameAndSave_keepMetadataAndCoreIdentitySeparate() {
        val repository = create(listOf(PeerEndpoint("tcp://192.0.2.10:11010")), Ipv4Assignment.Automatic)
        val first = repository.findSaved(profileId)!!
        repository.rename(profileId, " 本地新名称 ")
        assertEquals(first, repository.findSaved(profileId))
        val second = repository.save(profileId, first.core.copy(mtu = 1400), first.vpnPolicy)!!
        assertEquals(2L, second.revision)
        assertEquals(instanceId, second.core.instanceId)
        assertEquals(first.core.networkSecret, second.core.networkSecret)
        assertEquals("本地新名称", repository.profiles.value.single().displayName)
        assertThrows(IllegalArgumentException::class.java) {
            repository.save(profileId, second.core.copy(instanceId = CoreInstanceId("other")), second.vpnPolicy)
        }
        assertEquals(second, repository.findSaved(profileId))
    }

    /** 输入集合和已发布列表不能事后改写已保存修订；运行中的旧值保持独立。 */
    @Test
    fun mutableCallerCollections_cannotAlterSavedInput() {
        val peers = mutableListOf(PeerEndpoint("udp://192.0.2.10:11010"))
        val repository = create(peers, Ipv4Assignment.Automatic)
        val first = repository.findSaved(profileId)!!
        peers.clear()
        assertEquals(1, first.core.peers.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (first.core.peers as MutableList).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            (repository.profiles.value as MutableList).clear()
        }
        val dns = mutableListOf("10.42.0.54")
        val second = repository.save(profileId, first.core, first.vpnPolicy.copy(dnsServers = dns))!!
        dns.clear()
        assertEquals(listOf("10.42.0.54"), second.vpnPolicy.dnsServers)
        assertEquals(listOf("10.42.0.53"), first.vpnPolicy.dnsServers)
    }
    /** 初始名称与改名使用同一归一化规则；空白回退网络名称，去除非空名称首尾空白。 */
    @Test
    fun initialName_isTrimmedOrFallsBackToNetworkName() {
        listOf("" to "network", "  " to "network", " 本地名称 " to "本地名称").forEach { (name, expected) ->
            val repository = ConfigurationInput.create(
                profileId, instanceId, name, "network", NetworkSecret.of("synthetic-value"),
                listOf(PeerEndpoint("tcp://192.0.2.10:11010")), Ipv4Assignment.Automatic, emptyList(),
            )
            assertEquals(expected, repository.profiles.value.single().displayName)
            assertEquals(instanceId, repository.findSaved(profileId)!!.core.instanceId)
        }
    }
}
