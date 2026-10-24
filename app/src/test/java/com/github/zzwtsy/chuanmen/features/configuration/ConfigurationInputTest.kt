package com.github.zzwtsy.chuanmen.features.configuration

import com.github.zzwtsy.chuanmen.lib.profile.testDocument

import com.github.zzwtsy.chuanmen.lib.profile.CoreInstanceId
import com.github.zzwtsy.chuanmen.lib.profile.Ipv4Assignment
import com.github.zzwtsy.chuanmen.lib.profile.Ipv6Policy
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.NetworkSecret
import com.github.zzwtsy.chuanmen.lib.profile.PeerEndpoint
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest
import com.github.zzwtsy.chuanmen.lib.profile.*

class ConfigurationInputTest {
    private val profileId = LocalProfileId("00000000-0000-4000-8000-000000000022")
    private val instanceId = CoreInstanceId("controlled-instance")

    private fun createRepository(
        id: LocalProfileId, instance: CoreInstanceId, name: String, network: String, secret: NetworkSecret,
        peers: List<PeerEndpoint>, ipv4: Ipv4Assignment, dns: List<String>,
    ): MemoryProfileRepository {
        val core = CoreConfig(instance, network, null, ipv4, networkSecret = secret, peers = peers)
        val policy = AndroidVpnPolicy(dnsServers = dns)
        return MemoryProfileRepository(listOf(ProfileSummary(SavedRevision(id, 1, core, policy), name)))
    }

    private fun create(peers: List<PeerEndpoint>, ipv4: Ipv4Assignment) = createRepository(
        profileId, instanceId, "受控网络", "controlled-network", NetworkSecret.of("synthetic-controlled-value"),
        peers, ipv4, listOf("10.42.0.53"),
    )

    /** TCP/UDP 对端与静态/DHCP 四种组合都进入已保存输入；本地名称不参与核心身份。 */
    @Test
    fun transportAndAddressMatrix_isSavedWithoutStartingSession() = runTest {
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

    /** 空节点列表可以保存，但保存不构成连接成功；不擅自注入默认节点。 */
    @Test
    fun missingPeer_isSavedWithoutInjectingDefaults() = runTest {
        assertTrue(create(emptyList(), Ipv4Assignment.Automatic).findSaved(profileId)!!.core.peers.isEmpty())
    }

    /** 改本地名称后核心与修订不变；保存 MTU 只增加修订，不覆盖名称或实例身份。 */
    @Test
    fun renameAndSave_keepMetadataAndCoreIdentitySeparate() = runTest {
        val repository = create(listOf(PeerEndpoint("tcp://192.0.2.10:11010")), Ipv4Assignment.Automatic)
        val first = repository.findSaved(profileId)!!
        repository.rename(profileId, " 本地新名称 ")
        assertEquals(first, repository.findSaved(profileId))
        val second = repository.save(profileId, first.core.copy(mtu = 1400).testDocument(), first.vpnPolicy, first.revision).requireSuccess()
        assertEquals(2L, second.revision)
        assertEquals(instanceId, second.core.instanceId)
        assertEquals(first.core.networkSecret, second.core.networkSecret)
        assertEquals("本地新名称", repository.profiles.value.single().displayName)
        assertEquals(WriteResult.Failure(WriteError.ValidationError),
            repository.save(profileId, second.core.copy(instanceId = CoreInstanceId("other")).testDocument(), second.vpnPolicy, second.revision))
        assertEquals(second, repository.findSaved(profileId))
    }

    /** 输入集合和已发布列表不能事后改写已保存修订；运行中的旧值保持独立。 */
    @Test
    fun mutableCallerCollections_cannotAlterSavedInput() = runTest {
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
        val second = repository.save(profileId, first.core.testDocument(), first.vpnPolicy.copy(dnsServers = dns), first.revision).requireSuccess()
        dns.clear()
        assertEquals(listOf("10.42.0.54"), second.vpnPolicy.dnsServers)
        assertEquals(listOf("10.42.0.53"), first.vpnPolicy.dnsServers)
    }
    /** 初始名称与改名使用同一归一化规则；空白回退网络名称，去除非空名称首尾空白。 */
    @Test
    fun initialName_isTrimmedOrFallsBackToNetworkName() = runTest {
        listOf("" to "network", "  " to "network", " 本地名称 " to "本地名称").forEach { (name, expected) ->
            val repository = createRepository(
                profileId, instanceId, name, "network", NetworkSecret.of("synthetic-value"),
                listOf(PeerEndpoint("tcp://192.0.2.10:11010")), Ipv4Assignment.Automatic, emptyList(),
            )
            assertEquals(expected, repository.profiles.value.single().displayName)
            assertEquals(instanceId, repository.findSaved(profileId)!!.core.instanceId)
        }
    }
    /** 新建默认只供新建使用；已有缺省密码在修改 MTU 时不被显式空密码覆盖。 */
    @Test fun sharedFactory_preservesPresenceAndNewDefaults() {
        val factory = com.github.zzwtsy.chuanmen.features.configuration.lib.ConfigurationInputFactory
        val fresh = factory.forNew()
        assertTrue(fresh.automatic); assertEquals("1380", fresh.rawMtu); assertFalse(fresh.internet); assertEquals("", fresh.rawSecret)
        val saved = com.github.zzwtsy.chuanmen.testSavedRevision()
        val input = factory.fromSaved(saved)
        assertNull(input.rawSecret)
        val changes = factory.validate(input.copy(rawMtu = "1400"), saved).value!!.changes
        assertEquals(listOf(CoreConfigChange.Mtu(1400)), changes)
        val explicit = factory.validate(input.copy(rawSecret = ""), saved).value!!.changes.single() as CoreConfigChange.Secret
        assertEquals("", explicit.value!!.revealForCore())
    }

    /** 已有未接入节点可原样保留，修改其 URL 必须校验；新增空行报错而非静默丢弃。 */
    @Test fun sharedFactory_validatesOnlyChangedPeerUrisAndKeepsRows() {
        val factory = com.github.zzwtsy.chuanmen.features.configuration.lib.ConfigurationInputFactory
        val saved = com.github.zzwtsy.chuanmen.testSavedRevision().let { original -> original.copy(document =
            CoreConfigDocument("", original.core, "synthetic", listOf("peer.protocol"), sourcePeerUris = listOf("quic://192.0.2.1:11010"))) }
        val input = factory.fromSaved(saved)
        assertNotNull(factory.validate(input, saved).value)
        val edited = input.editPeer(com.github.zzwtsy.chuanmen.features.configuration.lib.PeerAction.ReplaceUri(input.peers.single().id, "invalid"))
        assertTrue(input.peers.single().id in factory.validate(edited, saved).peerErrors)
        val added = input.editPeer(com.github.zzwtsy.chuanmen.features.configuration.lib.PeerAction.Add)
        assertEquals(2, added.peers.size); assertTrue(added.peers.last().id in factory.validate(added, saved).peerErrors)
        assertEquals(input, added.editPeer(com.github.zzwtsy.chuanmen.features.configuration.lib.PeerAction.Remove(added.peers.last().id)))
    }

    /** 基础显隐、IPv4 启用和 IPv6 只读由同一字段目录决定，未接入字段不能进入目录。 */
    @Test fun typedFieldPolicies_driveVisibilityAndEnablement() {
        val fields = com.github.zzwtsy.chuanmen.features.configuration.lib.ConfigurationFields.all
        assertEquals(setOf("network_identity.network_name", "network_identity.network_secret", "peer"), fields.filter { it.basicVisible }.map { it.key }.toSet())
        val ipv4 = fields.single { it.key == "ipv4" }
        val input = com.github.zzwtsy.chuanmen.features.configuration.lib.ConfigurationInputFactory.forNew()
        assertFalse(ipv4.enabled(input)); assertTrue(ipv4.enabled(input.copy(automatic = false)))
        assertEquals(com.github.zzwtsy.chuanmen.features.configuration.lib.FieldInputType.ReadOnly, fields.single { it.key == "android.ipv6Policy" }.inputType)
        assertEquals(10, fields.size)
    }

}
