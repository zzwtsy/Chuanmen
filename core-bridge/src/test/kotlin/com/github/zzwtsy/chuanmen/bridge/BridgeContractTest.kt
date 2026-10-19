package com.github.zzwtsy.chuanmen.bridge

import com.github.zzwtsy.chuanmen.contracts.*
import com.google.gson.JsonParser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeContractTest {
    private val token = OperationToken(SessionId("synthetic-session", 2), 3)
    private val attachment = AttachmentId(token.sessionId, 4)
    private val spec = VpnInterfaceSpec(listOf(Ipv4Cidr("10.222.0.2", 24)), listOf(Ipv4Cidr("10.222.0.0", 24)), emptyList(), 1380)

    private fun reply(result: String, status: String = "confirmed", generation: Long = 2, attachmentSequence: Long = 4) =
        """{"protocol":1,"status":"$status","session_id":"synthetic-session","generation":$generation,"operation":3,"attachment":$attachmentSequence,"result":$result}"""

    /** 超过 Double 精确整数范围的累计数从协议读入后保留全部整数位。 */
    @Test fun integerCountersKeepPrecision() {
        val root = BridgeProtocolCodec.decode(reply("""{"phase":"running","core_state":"running","saved_revision":7,"last_operation":3,"ipv4":"10.222.0.2/24","peers":[],"routes":[],"udp_nat_type":null,"tcp_nat_type":null,"attachment":{"session_id":"synthetic-session","generation":2,"operation":3,"attachment":4},"attachment_phase":"installed","tun_counters":{"tx_bytes":9007199254740993,"rx_bytes":0,"tx_packets":1,"rx_packets":0},"active_instances":1,"callbacks_in_flight":0,"jni_global_refs":1,"protection_calls":1,"protection_failures":0}"""), token)
        val snapshot = BridgeProtocolCodec.snapshot(root, token)
        assertEquals(9_007_199_254_740_993L, snapshot.counters!!.txBytes)
        assertEquals(attachment, snapshot.attachmentId)
        assertEquals(Ipv4Cidr("10.222.0.2", 24), snapshot.ipv4)
    }

    /** 协议整数字段包含浮点、指数、溢出或字符串时拒绝，避免默默舍入。 */
    @Test fun malformedIntegerFieldsAreRejected() {
        for (value in listOf("1.0", "1e0", "9223372036854775808", "\"1\"", "-1")) {
            assertThrows(Exception::class.java) {
                BridgeProtocolCodec.decode("""{"protocol":$value,"status":"confirmed"}""")
            }
        }
    }

    /** 旧会话代次或 attachment 回执即使标记 confirmed，也不能归入当前操作。 */
    @Test fun receiptIdentityMustMatchExactly() {
        assertThrows(Exception::class.java) { BridgeProtocolCodec.decode(reply("{}", generation = 1), token) }
        assertThrows(Exception::class.java) { BridgeProtocolCodec.decode(reply("{}", attachmentSequence = 1), token, attachment) }
        assertThrows(Exception::class.java) { BridgeProtocolCodec.decode(reply("{}") + "{}", token) }
    }

    /** 提交安装后先保持无回执；后台确认后只发 TunAttached，不提前发整体 Ready。 */
    @Test fun installAcceptanceIsSeparateFromConfirmation() = runTest {
        val observed = mutableListOf<EngineEvent>()
        val engine = NativeCoreEngine(backgroundScope, EnginePort { _, _ -> reply("""{"installed":true,"borrow_ended":false,"attachment":4}""") }, StandardTestDispatcher(testScheduler), true)
        backgroundScope.launch { engine.events.collect(observed::add) }
        engine.submit(EngineCommand.InstallTun(token, attachment, 0, spec))
        assertTrue(observed.isEmpty())
        runCurrent()
        assertEquals(listOf(EngineEvent.TunAttached(token, TunInstalled(attachment, spec))), observed)
    }

    /** 实际订阅开始后计数增加，取消消费者后归零，不能用固定值伪报订阅释放。 */
    @Test fun lifecycleSubscriberCounterTracksCollectionAndCancellation() = runTest {
        val engine = NativeCoreEngine(backgroundScope, EnginePort { _, _ -> reply("{}") }, StandardTestDispatcher(testScheduler), true)
        assertEquals(0L, engine.lifecycleSubscriberCount)
        val subscription = backgroundScope.launch { engine.events.collect { } }
        runCurrent()
        assertEquals(1L, engine.lifecycleSubscriberCount)
        subscription.cancel()
        runCurrent()
        assertEquals(0L, engine.lifecycleSubscriberCount)
    }

    /** 原生 Apply 未实现时不宣称该能力；提交它明确失败且不调用 JNI 或伪造成功。 */
    @Test fun unsupportedNativeApplyIsNotAdvertisedAsCapability() = runTest {
        val events = mutableListOf<EngineEvent>()
        val engine = NativeCoreEngine(backgroundScope, EnginePort { _, _ -> error("不应调用原生 Apply") }, StandardTestDispatcher(testScheduler), true)
        assertFalse(Capability.ApplySavedRevision in engine.capabilities)
        backgroundScope.launch { engine.events.collect(events::add) }
        engine.submit(EngineCommand.Apply(token, saved()))
        runCurrent()
        assertEquals(listOf(EngineEvent.Failed(token, UnavailableReason.NotImplemented, FailureDisposition.PreviousRunning)), events)
    }

    /** 停止回复不能证明排空时，桥报告未知，调用方不能据此关闭 PFD。 */
    @Test fun stopWithoutDrainProofStaysUnknown() = runTest {
        val observed = mutableListOf<EngineEvent>()
        val engine = NativeCoreEngine(backgroundScope, EnginePort { _, _ -> reply("""{"drained":false}""") }, StandardTestDispatcher(testScheduler), true)
        backgroundScope.launch { engine.events.collect(observed::add) }
        engine.submit(EngineCommand.Stop(token))
        runCurrent()
        assertEquals(listOf(EngineEvent.OutcomeUnknown(token)), observed)
    }

    /** 结果归属错误时报告未知；不伪造失败已收束或停止已完成。 */
    @Test fun staleStopReceiptCannotReleaseResources() = runTest {
        val observed = mutableListOf<EngineEvent>()
        val engine = NativeCoreEngine(backgroundScope, EnginePort { _, _ -> reply("""{"drained":true}""", generation = 1) }, StandardTestDispatcher(testScheduler), true)
        backgroundScope.launch { engine.events.collect(observed::add) }
        engine.submit(EngineCommand.Stop(token))
        runCurrent()
        assertEquals(listOf(EngineEvent.OutcomeUnknown(token)), observed)
    }

    /** 未提供 protector 时启动在 JNI 前拒绝，不继承上游默认放行的行为。 */
    @Test fun startRequiresProtectorBeforeNativeCall() = runTest {
        var calls = 0
        val observed = mutableListOf<EngineEvent>()
        val engine = NativeCoreEngine(backgroundScope, EnginePort { _, _ -> calls++; error("不应调用 JNI") }, StandardTestDispatcher(testScheduler), true)
        backgroundScope.launch { engine.events.collect(observed::add) }
        engine.submit(EngineCommand.Start(token, saved()))
        runCurrent()
        assertEquals(0, calls)
        assertEquals(UnavailableReason.ProtectionFailed, (observed.single() as EngineEvent.Failed).reason)
    }

    /** 平台回调抛异常或收到非法 fd 时一律拒绝；正常回调收到精确会话代次。 */
    @Test fun callbackFailuresDoNotEscapeOrAllowSocket() {
        val throwing = ProtectorCallback(SocketProtector { _, _ -> throw IllegalStateException("synthetic-secret") })
        assertFalse(throwing.protectForNative(7, token.sessionId.value, 2))
        var seen: SessionId? = null
        val callback = ProtectorCallback(SocketProtector { _, id -> seen = id; true })
        assertFalse(callback.protectForNative(-1, token.sessionId.value, 2))
        assertTrue(callback.protectForNative(7, token.sessionId.value, 2))
        assertEquals(token.sessionId, seen)
    }

    /** 名称和引号转义不能注入 TOML 表；本地名称不进入核心，秘密只存在显式编码结果。 */
    @Test fun controlledConfigEscapesWithoutIdentityCoupling() {
        val saved = saved().let { it.copy(core = it.core.copy(networkName = "synthetic\"\n[flags]")) }
        val encoded = CoreConfigEncoder.encode(saved)
        assertTrue(encoded.contains("network_name = \"synthetic\\\"\\n[flags]\""))
        assertTrue(encoded.contains("network_secret = \"synthetic-public-test-only\""))
        assertFalse(saved.toString().contains("synthetic-public-test-only"))
        assertFalse(encoded.contains("localDisplayName"))
        assertTrue(encoded.contains("no_tun = false"))
    }

    /** 停止确认之后的引用释放必须独立核对 released 与引用计数，不能继承停止回执。 */
    @Test fun releaseUsesIndependentConfirmation() = runTest {
        var action: String? = null
        val engine = NativeCoreEngine(backgroundScope, EnginePort { json, _ ->
            action = JsonParser.parseString(json).asJsonObject.get("action").asString
            reply("""{"released":true,"jni_global_refs":0}""")
        }, StandardTestDispatcher(testScheduler), true)
        assertEquals(ConfirmationResult.Confirmed(Unit), engine.releaseSession(token))
        assertEquals("release", action)
    }

    private fun saved() = SavedRevision(
        LocalProfileId("00000000-0000-4000-8000-000000000002"), 7,
        CoreConfig(CoreInstanceId("stable-core"), "synthetic-network", null, Ipv4Assignment.Static("10.222.0.2"),
            networkSecret = NetworkSecret.of("synthetic-public-test-only"), peers = listOf(PeerEndpoint("tcp://127.0.0.1:11010"))),
        AndroidVpnPolicy(),
    )
}
