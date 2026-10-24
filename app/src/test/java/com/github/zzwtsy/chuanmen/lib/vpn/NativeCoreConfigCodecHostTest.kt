package com.github.zzwtsy.chuanmen.lib.vpn

import com.github.zzwtsy.chuanmen.features.configuration.ConfigurationUiState
import com.github.zzwtsy.chuanmen.lib.profile.*
import com.github.zzwtsy.chuanmen.testAppliedSnapshot
import com.github.zzwtsy.chuanmen.testSavedRevision
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** 独立任务加载真实宿主 JNI；不创建会话、TUN 或设备连接，不以替身代替编解码。 */
class NativeCoreConfigCodecHostTest {
    private val codec = NativeCoreConfigCodec()
    private val template = testSavedRevision()
    private val identity = template.core.instanceId
    private val base = "[network_identity]\nnetwork_name='合成 🌏'\nnetwork_secret=''\n"
    private suspend fun parse(raw: String) = (codec.parse(raw, identity) as ConfigResult.Success).value
    private fun projection(document: CoreConfigDocument): JsonObject {
        val request = JsonObject().apply {
            addProperty("protocol", 1); addProperty("action", "config_parse"); addProperty("config", document.raw)
        }
        val reply = JsonParser.parseString(JniEnginePort.request(request.toString(), null)).asJsonObject
        assertEquals("confirmed", reply["status"].asString)
        return reply.getAsJsonObject("result").getAsJsonObject("projection")
    }

    /** 无缺省字段与显式默认值分别往返 JNI，源语义不同但运行等价，不产生待应用。 */
    @Test fun resolvedDefaults_doNotCreatePendingApplication() = runBlocking {
        val first = parse(base)
        val explicit = parse("hostname=''\ndhcp=false\n$base[flags]\nmtu=1380\n")
        assertFalse(first.sameValues(explicit)); assertTrue(first.sameRuntimeValues(explicit))
        val appliedSaved = template.copy(document = first)
        val applied = testAppliedSnapshot(appliedSaved)
        val session = SessionState(phase = ConnectionPhase.Running, sessionId = applied.sessionId,
            requestedProfileId = template.profileId, applied = applied)
        val profiles = listOf(ProfileSummary(template.copy(revision = 2, document = explicit), "合成"))
        assertFalse(ConfigurationUiState.project(profiles, session, template.profileId, null).pendingApplication)
        assertFalse(first.sameRuntimeValues(parse("$base[flags]\nmtu=1400\n")))
        assertTrue(parse("ipv4='10.42.0.9'\n$base").sameRuntimeValues(parse("ipv4='10.42.0.9/24'\n$base")))
        val missingSecret = parse(base.replace("network_secret=''\n", ""))
        assertNull(missingSecret.core.networkSecret)
        assertEquals("", first.core.networkSecret!!.revealForCore())
        assertFalse(first.sameValues(missingSecret))
        assertTrue(first.sameRuntimeValues(missingSecret))
    }

    /** 同时修改已有节点 URL 并新增节点，未知嵌套值和 u64 穿过 Kotlin/JNI 后仍属于原节点。 */
    @Test fun replacingAndAddingPeer_preservesUnknownMembers() = runBlocking {
        val original = parse("${base}[[peer]]\nuri='tcp://192.0.2.1:11010'\nfuture={enabled=false,wide=18446744073709551615}\n")
        val patched = (codec.patch(original, listOf(CoreConfigChange.PeerEdits(listOf(
            PeerPatchEntry.ReplaceUri(0, PeerEndpoint("udp://192.0.2.2:11010")),
            PeerPatchEntry.Insert(PeerEndpoint("tcp://192.0.2.3:11010")),
        )))) as ConfigResult.Success).value
        assertEquals(listOf("udp://192.0.2.2:11010", "tcp://192.0.2.3:11010"), patched.sourcePeerUris)
        val peers = projection(patched).getAsJsonArray("peer")
        val future = peers[0].asJsonObject.getAsJsonObject("future")
        assertFalse(future["enabled"].asBoolean)
        assertEquals("18446744073709551615", future.getAsJsonObject("wide")["decimal"].asString)
        assertFalse(peers[1].asJsonObject.has("future"))
        assertEquals("tcp://192.0.2.1:11010", original.sourcePeerUris.single())
        assertEquals(ConfigProblemCode.UnsupportedField, (codec.encodeForRuntime(patched) as ConfigResult.Failure).problem.code)
        assertTrue(parse(patched.raw).sameValues(patched))
    }

    /** 相同 URL 按不同索引重排再替换及删除，未知标记不互换，重复或越界索引被原生边界拒绝。 */
    @Test fun duplicateUris_haveIndependentOrigins() = runBlocking {
        val original = parse("${base}[[peer]]\nuri='tcp://192.0.2.1:11010'\ntag='A'\n[[peer]]\nuri='tcp://192.0.2.1:11010'\ntag='B'\n")
        val patched = (codec.patch(original, listOf(CoreConfigChange.PeerEdits(listOf(
            PeerPatchEntry.Keep(1), PeerPatchEntry.ReplaceUri(0, PeerEndpoint("udp://192.0.2.2:11010")),
        )))) as ConfigResult.Success).value
        val peers = projection(patched).getAsJsonArray("peer")
        assertEquals("B", peers[0].asJsonObject["tag"].asString); assertEquals("A", peers[1].asJsonObject["tag"].asString)
        val removed = (codec.patch(patched, listOf(CoreConfigChange.PeerEdits(listOf(PeerPatchEntry.Keep(1))))) as ConfigResult.Success).value
        assertEquals("A", projection(removed).getAsJsonArray("peer")[0].asJsonObject["tag"].asString)
        for (entries in listOf(listOf(PeerPatchEntry.Keep(0), PeerPatchEntry.Keep(0)), listOf(PeerPatchEntry.Keep(-1)), listOf(PeerPatchEntry.Keep(2)))) {
            assertEquals(ConfigProblemCode.InvalidConfig, (codec.patch(original, listOf(CoreConfigChange.PeerEdits(entries))) as ConfigResult.Failure).problem.code)
        }
    }

    /** 已接入字段修改可以保留未知协议节点，保留不代表允许执行；正常文档仍能编码运行配置。 */
    @Test fun unsupportedPeers_areRetainedAndBlockedAtRuntime() = runBlocking {
        val original = parse("${base}[[peer]]\nuri='quic://192.0.2.1:11010'\nfuture=false\n")
        val changes = listOf(CoreConfigChange.Hostname("主机"), CoreConfigChange.PeerEdits(listOf(
            PeerPatchEntry.Keep(0), PeerPatchEntry.Insert(PeerEndpoint("tcp://192.0.2.2:11010")),
        )))
        val patched = (codec.patch(original, changes) as ConfigResult.Success).value
        assertEquals("quic://192.0.2.1:11010", patched.sourcePeerUris.first())
        assertEquals(ConfigProblemCode.UnsupportedField, (codec.encodeForRuntime(patched) as ConfigResult.Failure).problem.code)
        val valid = parse(base)
        val runtime = (codec.encodeForRuntime(valid) as ConfigResult.Success).value
        assertTrue(runtime.toml.contains("listeners = []")); assertFalse(runtime.toString().contains("network_secret"))
        assertFalse(valid.sameRuntimeValues(patched))
    }
    /** 合法托管监听与身份只改变源文档，运行投影覆盖这些值且不制造待应用或改写原件。 */
    @Test fun managedFields_doNotCreateRuntimeDifference() = runBlocking {
        val original = parse(base)
        val managed = parse("instance_name='source-name'\nlisteners=['tcp://0.0.0.0:11010']\n${base}[flags]\nno_tun=true\nenable_ipv6=true\n")
        assertFalse(original.sameValues(managed)); assertTrue(original.sameRuntimeValues(managed))
        val raw = managed.raw
        val runtime = (codec.encodeForRuntime(managed) as ConfigResult.Success).value
        assertTrue(runtime.overriddenFields.containsAll(listOf("listeners", "instance_name", "flags.no_tun", "flags.enable_ipv6")))
        assertTrue(runtime.toml.contains("listeners = []")); assertEquals(raw, managed.raw)
    }

}
