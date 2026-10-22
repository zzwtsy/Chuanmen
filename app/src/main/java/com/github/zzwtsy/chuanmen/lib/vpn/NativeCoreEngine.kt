package com.github.zzwtsy.chuanmen.lib.vpn

import com.github.zzwtsy.chuanmen.lib.profile.SavedRevision
import com.github.zzwtsy.chuanmen.lib.vpn.BridgeProtocolCodec.bool
import com.github.zzwtsy.chuanmen.lib.vpn.BridgeProtocolCodec.long
import com.github.zzwtsy.chuanmen.lib.vpn.BridgeProtocolCodec.string
import com.google.gson.JsonObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 正式 JNI 桥；后台命令可以与停止并行，生命周期回执不丢弃。
 * 只发布核心/TUN 各自的确认，整体 Running 由拥有 PFD 的 Service 决定。
 * 作用域由 Service 提供，必须在停止、PFD 关闭及 releaseSession 确认之后取消。
 */
class NativeCoreEngine internal constructor(
    private val scope: CoroutineScope,
    private val port: EnginePort,
    private val dispatcher: CoroutineDispatcher,
    private val requireAndroid: Boolean,
) : CoreEngine {
    constructor(scope: CoroutineScope) : this(scope, JniEnginePort, Dispatchers.IO, true)

    // 原生不提供热应用；Service 通过验证、完整停止和重连实现 ApplySavedRevision。
    override val capabilities = setOf(Capability.Connect, Capability.TunCounters)
    private val receipts = Channel<EngineEvent>(32)
    private val subscribers = AtomicLong()
    override val lifecycleSubscriberCount: Long get() = subscribers.get()
    override val events = receipts.receiveAsFlow().onStart { subscribers.incrementAndGet() }.onCompletion { subscribers.decrementAndGet() }
    private val cancelled = ConcurrentHashMap.newKeySet<SessionId>()
    private val notEntered = ConcurrentHashMap.newKeySet<SessionId>()
    private val admissionLock = Any()

    override fun submit(command: EngineCommand) {
        if (command is EngineCommand.Stop) synchronized(admissionLock) { cancelled += command.token.sessionId }
        scope.launch(dispatcher) {
            val event = when (command) {
                is EngineCommand.Start -> start(command)
                is EngineCommand.InstallTun -> mutate("attach", command.token, command.attachmentId, fields = {
                    addProperty("fd", command.fd)
                }) { result ->
                    require(result.bool("installed") && !result.bool("borrow_ended"))
                    require(result.long("attachment") == command.attachmentId.sequence)
                    EngineEvent.TunAttached(command.token, TunInstalled(command.attachmentId, command.spec))
                }
                is EngineCommand.RemoveTun -> mutate("detach", command.token, command.attachmentId) { result ->
                    require(result.bool("borrow_ended") && !result.bool("installed"))
                    require(result.long("attachment") == command.attachmentId.sequence)
                    EngineEvent.TunRemoved(command.token, command.attachmentId)
                }
                is EngineCommand.Stop -> if (command.token.sessionId in notEntered) {
                    EngineEvent.Stopped(command.token, StopConfirmation(command.token.sessionId, true))
                } else mutate("stop", command.token) { result ->
                    require(result.bool("drained"))
                    EngineEvent.Stopped(command.token, StopConfirmation(command.token.sessionId, true))
                }
                is EngineCommand.QueryRuntime -> {
                    val response = call(BridgeProtocolCodec.request("query", command.token), token = command.token)
                    event(response, command.token) { root ->
                        EngineEvent.CoreObserved(command.token, BridgeProtocolCodec.snapshot(root, command.token))
                    }
                }
                is EngineCommand.Apply -> EngineEvent.Failed(command.token, UnavailableReason.NotImplemented, FailureDisposition.PreviousRunning)
            }
            receipts.send(event)
        }
    }

    private suspend fun start(command: EngineCommand.Start): EngineEvent {
        val protector = command.protector ?: return beforeNativeFailure(command.token, UnavailableReason.ProtectionFailed)
        val build = buildInfo()
        if (build !is ConfirmationResult.Confirmed || (requireAndroid && build.value.platform != "android")) {
            return beforeNativeFailure(command.token, UnavailableReason.ProtocolMismatch)
        }
        val config = try { CoreConfigEncoder.encode(command.saved) } catch (_: IllegalArgumentException) {
            return beforeNativeFailure(command.token, UnavailableReason.InvalidConfig)
        }
        val request = BridgeProtocolCodec.request("start", command.token).apply {
            addProperty("saved_revision", command.saved.revision)
            addProperty("config", config)
        }
        synchronized(admissionLock) {
            if (command.token.sessionId in cancelled) return beforeNativeFailure(command.token, UnavailableReason.ServiceRevoked)
        }
        return event(call(request, ProtectorCallback(protector), command.token), command.token) { root ->
            val result = root.getAsJsonObject("result")
            require(result.bool("core_started") && result.long("saved_revision") == command.saved.revision)
            EngineEvent.CoreStarted(command.token, command.saved.revision)
        }
    }

    private fun beforeNativeFailure(token: OperationToken, reason: UnavailableReason): EngineEvent {
        notEntered += token.sessionId
        return EngineEvent.Failed(token, reason, FailureDisposition.Stopped)
    }

    override suspend fun buildInfo(): ConfirmationResult<CoreBuildInfo> = decodeResult(call(BridgeProtocolCodec.request("build_info"))) { root ->
        val result = root.getAsJsonObject("result")
        require(result.string("core_revision") == BridgeProtocolCodec.CORE_REVISION)
        val features = result.getAsJsonArray("features").map { it.asString }.toSet()
        require(features.containsAll(setOf("session-confirmations", "tun-counters")))
        val abi = result.string("abi")
        require(abi in setOf("arm64-v8a", "x86_64"))
        CoreBuildInfo(BridgeProtocol.VERSION, result.string("core_revision"), result.string("core_version"),
            result.string("bridge_version"), abi, result.string("platform"), features)
    }

    override suspend fun validate(saved: SavedRevision): ConfirmationResult<Unit> {
        val config = try { CoreConfigEncoder.encode(saved) } catch (_: IllegalArgumentException) {
            return ConfirmationResult.Failed(CoreFailure(CoreErrorCode.InvalidConfig))
        }
        return decodeResult(call(BridgeProtocolCodec.request("validate").apply { addProperty("config", config) })) { root ->
            require(root.getAsJsonObject("result").bool("valid"))
        }
    }

    override suspend fun releaseSession(token: OperationToken): ConfirmationResult<Unit> {
        val result = if (token.sessionId in notEntered) ConfirmationResult.Confirmed(Unit) else decodeResult(
            call(BridgeProtocolCodec.request("release", token), token = token),
        ) { root ->
            val value = root.getAsJsonObject("result")
            require(value.bool("released") && value.long("jni_global_refs") == 0L)
        }
        if (result is ConfirmationResult.Confirmed) { cancelled -= token.sessionId; notEntered -= token.sessionId }
        return result
    }

    override suspend fun diagnostics(): ConfirmationResult<CoreDiagnostics> = decodeResult(call(BridgeProtocolCodec.request("diagnostics"))) { root ->
        val result = root.getAsJsonObject("result")
        val capacity = result.long("capacity").also { require(it in 1..1024) }.toInt()
        val records = result.getAsJsonArray("records").map { it.asJsonObject.let { record ->
            CoreDiagnostic(record.long("elapsed_millis"), record.string("scope"), record.string("level"), record.string("target"), record.string("code"))
        } }
        require(records.size <= capacity)
        CoreDiagnostics(records, result.long("dropped"), capacity)
    }

    private suspend fun mutate(
        action: String, token: OperationToken, attachment: AttachmentId? = null,
        fields: JsonObject.() -> Unit = {}, confirmed: (JsonObject) -> EngineEvent,
    ): EngineEvent = event(call(BridgeProtocolCodec.request(action, token, attachment).apply(fields), token = token, attachment = attachment), token) {
        confirmed(it.getAsJsonObject("result"))
    }

    private suspend fun call(request: JsonObject, callback: ProtectorCallback? = null, token: OperationToken? = null, attachment: AttachmentId? = null): ConfirmationResult<JsonObject> =
        withContext(dispatcher) {
            try {
                val encoded = request.toString()
                require(encoded.toByteArray(Charsets.UTF_8).size <= 256 * 1024)
                val root = BridgeProtocolCodec.decode(port.request(encoded, callback), token, attachment)
                if (root.string("status") == "confirmed") ConfirmationResult.Confirmed(root)
                else {
                    val failure = CoreFailure(errorCode(root.string("code")))
                    if (root.string("status") == "unknown") ConfirmationResult.Unknown(failure) else ConfirmationResult.Failed(failure)
                }
            } catch (_: LinkageError) {
                ConfirmationResult.Unknown(CoreFailure(CoreErrorCode.NativeFailure))
            } catch (_: Exception) {
                // 无法核对协议/归属时不能把失败解释为尚未执行。
                ConfirmationResult.Unknown(CoreFailure(CoreErrorCode.ProtocolMismatch))
            }
        }

    private fun <T> decodeResult(result: ConfirmationResult<JsonObject>, decode: (JsonObject) -> T): ConfirmationResult<T> = when (result) {
        is ConfirmationResult.Confirmed -> try { ConfirmationResult.Confirmed(decode(result.value)) }
            catch (_: Exception) { ConfirmationResult.Unknown(CoreFailure(CoreErrorCode.ProtocolMismatch)) }
        is ConfirmationResult.Failed -> result
        is ConfirmationResult.Unknown -> result
    }

    private fun event(result: ConfirmationResult<JsonObject>, token: OperationToken, confirmed: (JsonObject) -> EngineEvent): EngineEvent = when (val decoded = decodeResult(result, confirmed)) {
        is ConfirmationResult.Confirmed -> decoded.value
        is ConfirmationResult.Unknown -> EngineEvent.OutcomeUnknown(token)
        is ConfirmationResult.Failed -> EngineEvent.Failed(token, reason(decoded.failure.code), FailureDisposition.Unknown)
    }

    private fun errorCode(code: String): CoreErrorCode = when (code) {
        "invalid_config", "invalid_request", "invalid_identity", "invalid_revision", "input_too_large" -> CoreErrorCode.InvalidConfig
        "protocol_mismatch" -> CoreErrorCode.ProtocolMismatch
        "protector_unavailable" -> CoreErrorCode.ProtectorUnavailable
        "queue_closed" -> CoreErrorCode.QueueClosed
        "tun_install_failed", "invalid_fd", "invalid_attachment", "stale_attachment" -> CoreErrorCode.TunInstallFailed
        "partial_initialization" -> CoreErrorCode.PartialInitialization
        "cancelled", "session_closing" -> CoreErrorCode.ServiceRevoked
        else -> CoreErrorCode.NativeFailure
    }

    private fun reason(code: CoreErrorCode): UnavailableReason = when (code) {
        CoreErrorCode.InvalidConfig -> UnavailableReason.InvalidConfig
        CoreErrorCode.ProtocolMismatch -> UnavailableReason.ProtocolMismatch
        CoreErrorCode.ProtectorUnavailable, CoreErrorCode.ProtectionFailed, CoreErrorCode.NetworkBindingFailed -> UnavailableReason.ProtectionFailed
        CoreErrorCode.TunInstallFailed, CoreErrorCode.QueueClosed -> UnavailableReason.TunInstallFailed
        CoreErrorCode.ServiceRevoked -> UnavailableReason.ServiceRevoked
        else -> UnavailableReason.EngineRejected
    }
}
