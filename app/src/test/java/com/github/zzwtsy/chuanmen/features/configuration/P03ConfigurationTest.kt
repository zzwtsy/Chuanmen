package com.github.zzwtsy.chuanmen.features.configuration

import androidx.lifecycle.ViewModelStore
import com.github.zzwtsy.chuanmen.features.configuration.lib.*
import com.github.zzwtsy.chuanmen.lib.profile.*
import com.github.zzwtsy.chuanmen.lib.vpn.RecordingSessions
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import com.github.zzwtsy.chuanmen.testSavedRevision
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class P03ConfigurationTest {
    /** 首批黄金清单逐项经生产动作保存，文档及 Android 策略匹配且本地身份不变。 */
    @Test fun firstBatch_fieldsRoundTripThroughEditor() = runTest {
        val fixture = JsonParser.parseString(javaClass.getResourceAsStream("/profiles/p03-fields.json")!!.bufferedReader().use { it.readText() }).asJsonArray
        assertEquals(fixture.map { it.asJsonObject["key"].asString }.toSet(), ConfigurationFields.all.map { it.key }.toSet())
        val expected = fixture.associate { it.asJsonObject["key"].asString to it.asJsonObject["value"] }
        val initial = testSavedRevision()
        val repository = MemoryProfileRepository(listOf(ProfileSummary(initial, "本地名称")))
        withConfiguration(repository) { h ->
            ConfigurationFields.all.forEach { entry ->
                val field = entry.field ?: return@forEach
                val value = expected.getValue(entry.key)
                if (field == DraftField.Peers) {
                    h.draft.input.peers.forEach { h.act(ConfigurationAction.Peer(PeerAction.Remove(it.id))) }
                    value.asJsonArray.forEach { node ->
                        h.act(ConfigurationAction.Peer(PeerAction.Add))
                        h.act(ConfigurationAction.Peer(PeerAction.ReplaceUri(h.draft.input.peers.last().id, node.asJsonObject["uri"].asString)))
                    }
                } else h.edit(field, if (field == DraftField.Dns) value.asJsonArray.joinToString("\n") { it.asString } else value.asString)
            }
            h.act(ConfigurationAction.ChangeMode(EditorMode.Full)); h.act(ConfigurationAction.ChangeMode(EditorMode.Basic))
            h.act(ConfigurationAction.Save)
            val saved = repository.findSaved(initial.profileId)!!
            assertEquals(expected.getValue("network_identity.network_name").asString, saved.core.networkName)
            assertEquals(expected.getValue("network_identity.network_secret").asString, saved.core.networkSecret!!.revealForCore())
            assertEquals(expected.getValue("hostname").asString, saved.core.hostname)
            assertEquals(Ipv4Assignment.Static("10.42.8.9/20"), saved.core.ipv4)
            assertEquals(listOf(PeerEndpoint("udp://192.0.2.10:11010")), saved.core.peers)
            assertEquals(expected.getValue("flags.mtu").asInt, saved.core.mtu)
            assertEquals(expected.getValue("android.internetRouting").asBoolean, saved.vpnPolicy.includeInternet)
            assertEquals(expected.getValue("android.dnsServers").asJsonArray.map { it.asString }, saved.vpnPolicy.dnsServers)
            assertEquals(expected.getValue("android.ipv6Policy").asString, saved.vpnPolicy.ipv6.name)
            assertEquals(initial.core.instanceId, saved.core.instanceId)
            assertEquals("本地名称", repository.profiles.value.single().displayName)
            assertFalse(h.draft.dirty)
        }
    }
    /** 已有网络名称含空白时只改 MTU，生产保存不得更改未编辑的核心网络身份。 */
    @Test fun editingOtherField_preservesExactExistingNetworkIdentity() = runTest {
        val first = testSavedRevision().let { it.copy(document = it.core.copy(networkName = " network ").testDocument()) }
        withConfiguration(MemoryProfileRepository(listOf(ProfileSummary(first, "A")))) { h ->
            h.edit(DraftField.Mtu, "1400"); h.act(ConfigurationAction.Save)
            assertEquals(" network ", h.repository.findSaved(first.profileId)!!.core.networkName)
            assertEquals(1400, h.repository.findSaved(first.profileId)!!.core.mtu)
        }
    }
    /** 地址及 DNS 原文无效时切换标签再保存，输入错误保留且仓库不改变。 */
    @Test fun policyAndAddressErrors_keepRawAcrossModes() = runTest {
        val first = testSavedRevision()
        withConfiguration(MemoryProfileRepository(listOf(ProfileSummary(first, "A")))) { h ->
            h.edit(DraftField.Automatic, "false"); h.edit(DraftField.Ipv4, "10.42.0.1/33"); h.edit(DraftField.Dns, "not-an-ip")
            h.act(ConfigurationAction.ChangeMode(EditorMode.Full)); h.act(ConfigurationAction.ChangeMode(EditorMode.Basic)); h.act(ConfigurationAction.Save)
            assertEquals("10.42.0.1/33", h.draft.rawIpv4)
            assertEquals(setOf(DraftField.Ipv4, DraftField.Dns), h.draft.errors.keys)
            assertEquals(first, h.repository.findSaved(first.profileId))
        }
    }
    /** 外部保存提高修订后冲突；保留不改变基准、不自动重试，只有显式重载采用新版本。 */
    @Test fun conflict_keepsDraftAndRevisionUntilExplicitReload() = runTest {
        val first = testSavedRevision(); val repository = MemoryProfileRepository(listOf(ProfileSummary(first, "A")))
        withConfiguration(repository) { h ->
            h.edit(DraftField.Mtu, "1400")
            val latest = repository.save(first.profileId, first.core.copy(mtu = 1450).testDocument(), first.vpnPolicy, 1).requireSuccess()
            h.act(ConfigurationAction.Save)
            assertTrue(h.draft.conflict); assertEquals(1L, h.draft.base.revision); assertEquals("1400", h.draft.rawMtu)
            h.act(ConfigurationAction.KeepDraft); h.act(ConfigurationAction.Save)
            assertEquals(1L, h.draft.base.revision); assertEquals(latest, repository.findSaved(first.profileId))
            h.act(ConfigurationAction.Reload); assertFalse(h.draft.conflict); assertEquals("1450", h.draft.rawMtu)
        }
    }

    /** 保存 A 在途时再次保存被拒绝；改名、继续输入并切到 B 后，A 回执不会覆盖 B 或新输入。 */
    @Test fun inFlightSave_isSingleAndIsolatedFromCurrentView() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val a = testSavedRevision()
            val b = testSavedRevision(LocalProfileId("00000000-0000-4000-8000-000000000002"))
            val memory = MemoryProfileRepository(listOf(ProfileSummary(a, "A"), ProfileSummary(b, "B")))
            val gate = CompletableDeferred<Unit>()
            var writes = 0
            val repository = object : ProfileRepository by memory {
                override suspend fun save(profileId: LocalProfileId, document: CoreConfigDocument, policy: AndroidVpnPolicy, expectedRevision: Long): WriteResult<SavedRevision> {
                    writes++; gate.await()
                    return memory.save(profileId, document, policy, expectedRevision)
                }
            }
            val viewed = MutableStateFlow<LocalProfileId?>(a.profileId)
            val sessions = RecordingSessions(SessionState())
            val model = ConfigurationViewModel(repository, sessions, viewed, com.github.zzwtsy.chuanmen.lib.profile.TestCoreConfigCodec(), StandardTestDispatcher(testScheduler))
            store.put("configuration", model)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
            runCurrent()
            model.act(ConfigurationAction.Edit(DraftField.Mtu, "1400")); model.act(ConfigurationAction.Save); runCurrent()
            assertTrue(model.state.value.draft!!.saving)
            model.act(ConfigurationAction.Save); runCurrent(); assertEquals(1, writes)
            memory.rename(a.profileId, "新名")
            model.act(ConfigurationAction.Edit(DraftField.Mtu, "new-invalid"))
            viewed.value = b.profileId; runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals(b.profileId, model.state.value.profile!!.saved.profileId)
            assertNull(model.state.value.message)
            assertEquals("新名", memory.profiles.value.first().displayName)
            assertEquals(1400, memory.findSaved(a.profileId)!!.core.mtu)
            viewed.value = a.profileId; runCurrent()
            assertEquals("new-invalid", model.state.value.draft!!.rawMtu)
            assertTrue(model.state.value.draft!!.dirty)
            assertFalse(model.state.value.draft!!.saving)
            assertTrue(sessions.requests.isEmpty())
        } finally { store.clear(); runCurrent(); Dispatchers.resetMain() }
    }

    /** 归约器只确认提交前的序号；重复回执、重置后的新代次及删除后的槽位均不接受旧令牌。 */
    @Test fun editSequence_andDeletionRejectStaleReceipts() {
        val first = testSavedRevision(); val editor = ConfigurationEditor(); val id = first.profileId
        editor.open(first); editor.edit(id, DraftField.Mtu, "1400")
        val submitted = editor.beginSave(id)!!
        editor.edit(id, DraftField.Mtu, "1450"); editor.edit(id, DraftField.Mtu, "1400")
        val saved = first.copy(revision = 2, document = first.core.copy(mtu = 1400).testDocument())
        editor.acceptSaved(submitted, saved)
        assertTrue(editor.drafts.value.getValue(id).dirty)
        assertTrue(editor.drafts.value.getValue(id).editSequence > submitted.draft.editSequence)
        assertTrue(editor.discard(saved)); editor.edit(id, DraftField.Mtu, "1450")
        assertEquals(submitted.draft.editSequence, editor.drafts.value.getValue(id).editSequence)
        editor.acceptSaved(submitted, saved)
        assertEquals("1450", editor.drafts.value.getValue(id).rawMtu)
        editor.forget(id); editor.acceptSaved(submitted, saved); assertFalse(id in editor.drafts.value)
    }

    /** 搜索按相同目录过滤 key 和中文标签，去空白并忽略英文大小写，不把 P05 项放进结果。 */
    @Test fun search_usesOnlyFirstBatchAndRedactsSecretActions() {
        assertEquals(listOf("android.dnsServers"), ConfigurationFields.all.filter { it.matches("  DNS  ") }.map { it.key })
        assertEquals(listOf("hostname"), ConfigurationFields.all.filter { it.matches("主机名") }.map { it.key })
        assertEquals(10, ConfigurationFields.all.filter { it.matches(" ") }.size)
        assertFalse(ConfigurationAction.Edit(DraftField.Secret, "synthetic-private").toString().contains("synthetic-private"))
    }
    /** 保存中重置动作在状态层被拒绝，后续输入 1450 不被 1400 的成功回执覆盖。 */
    @Test fun discardDuringSave_isRejectedWithoutLosingNewInput() = runTest {
        val first = testSavedRevision(); val memory = MemoryProfileRepository(listOf(ProfileSummary(first, "A")))
        val gate = CompletableDeferred<Unit>(); var writes = 0
        val repository = object : ProfileRepository by memory {
            override suspend fun save(profileId: LocalProfileId, document: CoreConfigDocument, policy: AndroidVpnPolicy, expectedRevision: Long): WriteResult<SavedRevision> {
                writes++; gate.await(); return memory.save(profileId, document, policy, expectedRevision)
            }
        }
        withConfiguration(repository) { h ->
            h.edit(DraftField.Mtu, "1400"); h.act(ConfigurationAction.Save)
            val epoch = h.draft.epoch
            h.act(ConfigurationAction.Discard); h.act(ConfigurationAction.Reload)
            assertEquals(epoch, h.draft.epoch); assertTrue(h.draft.saving); assertEquals("1400", h.draft.rawMtu)
            h.edit(DraftField.Mtu, "1450"); h.act(ConfigurationAction.Save); assertEquals(1, writes)
            gate.complete(Unit); runCurrent()
            assertEquals(1400, memory.findSaved(first.profileId)!!.core.mtu)
            assertEquals("1450", h.draft.rawMtu); assertTrue(h.draft.dirty); assertFalse(h.draft.saving)
            h.act(ConfigurationAction.Save); assertEquals(2, writes); assertEquals(1450, memory.findSaved(first.profileId)!!.core.mtu)
        }
    }

    /** 保存期间新增的节点没有旧索引，已有行按提交顺序重建基准后再次保存仍正确关联。 */
    @Test fun peerOrigins_rebaseWithoutDiscardingEditsAfterSubmission() = runTest {
        val first = testSavedRevision(); val memory = MemoryProfileRepository(listOf(ProfileSummary(first, "A")))
        val gate = CompletableDeferred<Unit>()
        val repository = object : ProfileRepository by memory {
            override suspend fun save(profileId: LocalProfileId, document: CoreConfigDocument, policy: AndroidVpnPolicy, expectedRevision: Long): WriteResult<SavedRevision> {
                gate.await(); return memory.save(profileId, document, policy, expectedRevision)
            }
        }
        withConfiguration(repository) { h ->
            h.act(ConfigurationAction.Peer(PeerAction.Add)); val a = h.draft.input.peers.single().id
            h.act(ConfigurationAction.Peer(PeerAction.ReplaceUri(a, "tcp://192.0.2.1:11010")))
            h.act(ConfigurationAction.Save)
            h.act(ConfigurationAction.Peer(PeerAction.ReplaceUri(a, "udp://192.0.2.2:11010")))
            h.act(ConfigurationAction.Peer(PeerAction.Add)); val b = h.draft.input.peers.last().id
            h.act(ConfigurationAction.Peer(PeerAction.ReplaceUri(b, "tcp://192.0.2.3:11010")))
            h.act(ConfigurationAction.Peer(PeerAction.Move(b, -1)))
            gate.complete(Unit); runCurrent()
            assertEquals(listOf(b, a), h.draft.input.peers.map { it.id })
            assertEquals(listOf(null, 0), h.draft.input.peers.map { it.sourceIndex })
            h.act(ConfigurationAction.Save)
            assertEquals(listOf("tcp://192.0.2.3:11010", "udp://192.0.2.2:11010"), memory.findSaved(first.profileId)!!.document.sourcePeerUris)
            assertFalse(h.draft.dirty)
        }
    }

    /** codec 取消提交后 finally 解除对应保存状态，再次显式保存可以成功，不误发应用。 */
    @Test fun cancelledSave_releasesOnlyItsOwnSubmission() = runTest {
        val first = testSavedRevision(); val repository = MemoryProfileRepository(listOf(ProfileSummary(first, "A")))
        var cancel = true
        val codec = object : CoreConfigCodec by TestCoreConfigCodec() {
            override suspend fun patch(document: CoreConfigDocument, changes: List<CoreConfigChange>): ConfigResult<CoreConfigDocument> {
                if (cancel) throw CancellationException("synthetic")
                return TestCoreConfigCodec().patch(document, changes)
            }
        }
        withConfiguration(repository, codec = codec) { h ->
            h.edit(DraftField.Mtu, "1400"); h.act(ConfigurationAction.Save)
            assertFalse(h.draft.saving); assertTrue(h.draft.dirty); assertEquals(first, repository.findSaved(first.profileId))
            cancel = false; h.act(ConfigurationAction.Save)
            assertEquals(1400, repository.findSaved(first.profileId)!!.core.mtu); assertFalse(h.draft.dirty)
            assertTrue(h.sessions.requests.isEmpty())
        }
    }

    /** 新建首次提交中锁定所有输入和取消，失败恢复原值，重试只生成一份配置且结果保留到消费。 */
    @Test fun creationSubmission_locksInputAndRetainsFailureAndResult() = runTest {
        val memory = MemoryProfileRepository(emptyList()); val gate = CompletableDeferred<Unit>(); var writes = 0
        val repository = object : ProfileRepository by memory {
            override suspend fun create(document: CoreConfigDocument, policy: AndroidVpnPolicy, localDisplayName: String?): WriteResult<SavedRevision> {
                writes++; gate.await()
                if (writes == 1) return WriteResult.Failure(WriteError.WriteFailed)
                return memory.create(document, policy, localDisplayName)
            }
        }
        withConfiguration(repository) { h ->
            h.model.actCreation(CreationAction.Begin)
            h.model.actCreation(CreationAction.Edit(DraftField.NetworkName, "new-network"))
            h.model.actCreation(CreationAction.Edit(DraftField.Secret, "synthetic-create-secret"))
            h.model.actCreation(CreationAction.Name("本地名"))
            val input = h.model.creation.value!!.input
            h.model.actCreation(CreationAction.Save); runCurrent()
            h.model.actCreation(CreationAction.Edit(DraftField.NetworkName, "ignored"))
            h.model.actCreation(CreationAction.Name("ignored")); h.model.actCreation(CreationAction.Cancel)
            h.model.actCreation(CreationAction.Save); runCurrent()
            assertEquals(1, writes); assertEquals(input, h.model.creation.value!!.input)
            assertEquals("本地名", h.model.creation.value!!.localName); assertTrue(h.model.creation.value!!.saving)
            gate.complete(Unit); runCurrent()
            assertFalse(h.model.creation.value!!.saving); assertEquals(input, h.model.creation.value!!.input)
            assertNotNull(h.model.creation.value!!.notice); assertTrue(memory.profiles.value.isEmpty())
            h.model.actCreation(CreationAction.Save); runCurrent()
            val completed = h.model.creation.value!!
            assertEquals(memory.profiles.value.single().saved.profileId, completed.createdId)
            assertEquals("本地名", memory.profiles.value.single().displayName)
            assertEquals("synthetic-create-secret", memory.profiles.value.single().saved.core.networkSecret!!.revealForCore())
            assertEquals("", completed.input.rawSecret)
            h.model.actCreation(CreationAction.Save); runCurrent(); assertEquals(2, writes)
            h.model.actCreation(CreationAction.Acknowledge(completed.token!!)); assertNull(h.model.creation.value)
            h.model.actCreation(CreationAction.Begin); assertEquals("", h.model.creation.value!!.input.rawSecret)
            assertTrue(h.sessions.requests.isEmpty())
        }
    }

}
