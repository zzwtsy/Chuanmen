package com.github.zzwtsy.chuanmen.lib.vpn

import com.github.zzwtsy.chuanmen.lib.profile.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** JNI 的请求/回执边界测试；固定核心解析语义由 Rust 宿主测试独立验证。 */
class NativeCoreConfigCodecTest {
    private class ScriptedPort(var reply: String) : EnginePort {
        val requests = mutableListOf<JsonObject>()
        override fun request(json: String, callback: ProtectorCallback?): String {
            assertNull(callback)
            requests += JsonParser.parseString(json).asJsonObject
            return reply
        }
    }
    private val identity = CoreInstanceId("synthetic-codec-instance")
    private val parsed = """{"protocol":1,"status":"confirmed","result":{"codec_version":2,
        "raw":"[network_identity]\nnetwork_name='network'\nnetwork_secret=''\n",
        "projection":{"dhcp":true,"network_identity":{"network_name":"network","network_secret":""},"flags":{"mtu":{"type":"number","decimal":"1400"}},"peer":[]},
        "semantic":{"network_identity":{"network_name":"network","network_secret":""}},"unsupported":[],"runnable":true,"overrides":[]}}
    """.trimIndent()

    /** 无会话解析请求携带原文，回执中的空密码保持显式空值；u64 相关数值不经浮点读取。 */
    @Test fun parse_usesCodecActionAndRedactedDocument() = runTest {
        val port = ScriptedPort(parsed)
        val codec = NativeCoreConfigCodec(port, StandardTestDispatcher(testScheduler))
        val result = codec.parse("synthetic-source", identity) as ConfigResult.Success
        assertEquals("config_parse", port.requests.single()["action"].asString)
        assertFalse(port.requests.single().has("session_id"))
        assertEquals(identity, result.value.core.instanceId)
        assertEquals(1400, result.value.core.mtu)
        assertEquals("", result.value.core.networkSecret!!.revealForCore())
        assertEquals("CoreConfigDocument(<redacted>)", result.value.toString())
    }

    /** JSON 转义使原文放大超过 256 KiB 时，JNI 调用数必须为零并报告 RequestTooLarge。 */
    @Test fun escapedRequestLimit_isCheckedBeforeJni() = runTest {
        val port = ScriptedPort(parsed)
        val codec = NativeCoreConfigCodec(port, StandardTestDispatcher(testScheduler))
        val result = codec.parse("\u0001".repeat(45_000), identity)
        assertEquals(ConfigResult.Failure(ConfigProblem(ConfigProblemCode.RequestTooLarge)), result)
        assertTrue(port.requests.isEmpty())
    }

    /** 单项文档超过 128 KiB 时在构造 JNI 请求前拒绝，错误和返回对象不带原文。 */
    @Test fun documentLimit_doesNotCallJni() = runTest {
        val port = ScriptedPort(parsed)
        val codec = NativeCoreConfigCodec(port, StandardTestDispatcher(testScheduler))
        assertEquals(ConfigResult.Failure(ConfigProblem(ConfigProblemCode.DocumentTooLarge)),
            codec.parse("x".repeat(NativeCoreConfigCodec.MAX_DOCUMENT_BYTES + 1), identity))
        assertTrue(port.requests.isEmpty())
    }

    /** 尚未接入的参数必须以差异列表阻止运行；不把缺少运行 TOML 解释为成功。 */
    @Test fun unsupportedRuntime_isExplicitFailure() = runTest {
        val port = ScriptedPort(parsed)
        val codec = NativeCoreConfigCodec(port, StandardTestDispatcher(testScheduler))
        val document = (codec.parse("source", identity) as ConfigResult.Success).value
        port.reply = """{"protocol":1,"status":"confirmed","result":{"codec_version":2,"runnable":false,"unsupported":["acl"],"overrides":[]}}"""
        val result = codec.encodeForRuntime(document)
        assertEquals(ConfigResult.Failure(ConfigProblem(ConfigProblemCode.UnsupportedField, unsupportedFields = listOf("acl"))), result)
        assertEquals("config_runtime", port.requests.last()["action"].asString)
        assertEquals(identity.value, port.requests.last().getAsJsonObject("patch")["instance_name"].asString)
    }
    /** false 且缺省静态地址不能投影成 DHCP；自动模式下源静态前缀仍保留给同一草稿。 */
    @Test fun addressProjection_preservesMissingAndDormantStaticAddress() = runTest {
        val response = JsonParser.parseString(parsed).asJsonObject
        val fields = response.getAsJsonObject("result").getAsJsonObject("projection")
        fields.addProperty("dhcp", false)
        val port = ScriptedPort(response.toString())
        val codec = NativeCoreConfigCodec(port, StandardTestDispatcher(testScheduler))
        val missing = (codec.parse("source", identity) as ConfigResult.Success).value
        assertEquals(Ipv4Assignment.Unspecified, missing.core.ipv4)
        assertNull(missing.staticAddress)
        fields.addProperty("dhcp", true); fields.addProperty("ipv4", "10.42.0.1/32")
        port.reply = response.toString()
        val automatic = (codec.parse("source", identity) as ConfigResult.Success).value
        assertEquals(Ipv4Assignment.Automatic, automatic.core.ipv4)
        assertEquals("10.42.0.1/32", automatic.staticAddress)
    }

    /** 未支持协议仍作为文档和输入原文保留；运行错误保持明确，不能在 Kotlin 投影时丢成语法失败。 */
    @Test fun unknownPeerProtocol_isRetainedAsDocumentDifference() = runTest {
        val response = JsonParser.parseString(parsed).asJsonObject
        val result = response.getAsJsonObject("result")
        result.getAsJsonObject("projection").add("peer", JsonParser.parseString("""[{"uri":"quic://192.0.2.1:11010"}]"""))
        result.add("unsupported", JsonParser.parseString("""["peer.protocol"]"""))
        val codec = NativeCoreConfigCodec(ScriptedPort(response.toString()), StandardTestDispatcher(testScheduler))
        val document = (codec.parse("source", identity) as ConfigResult.Success).value
        assertEquals(listOf("quic://192.0.2.1:11010"), document.sourcePeerUris)
        assertEquals(listOf("peer.protocol"), document.unsupportedFields)
        assertTrue(document.core.peers.isEmpty())
    }

    /** 协议版本为小数时不能截断成整数成功；返回安全协议错误，不把回执原文带到 UI。 */
    @Test fun fractionalProtocol_isRejected() = runTest {
        val codec = NativeCoreConfigCodec(ScriptedPort(parsed.replace("\"protocol\":1", "\"protocol\":1.5")), StandardTestDispatcher(testScheduler))
        assertEquals(ConfigResult.Failure(ConfigProblem(ConfigProblemCode.Protocol)), codec.parse("source", identity))
    }

    /** 旧版本及小数 codec 版本均不能冒充 v2，回执在文档构造前返回协议错误。 */
    @Test fun incompatibleCodecVersions_areRejected() = runTest {
        for (version in listOf("1", "2.5")) {
            val reply = parsed.replace("\"codec_version\":2", "\"codec_version\":$version")
            val codec = NativeCoreConfigCodec(ScriptedPort(reply), StandardTestDispatcher(testScheduler))
            assertEquals(ConfigResult.Failure(ConfigProblem(ConfigProblemCode.Protocol)), codec.parse("source", identity))
        }
    }

}
