package com.github.zzwtsy.chuanmen.contracts

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 每轮连接的会话身份；[generation] 必须递增，过期结果不得更新新会话。 */
data class SessionId(val value: String, val generation: Long) {
    init {
        require(value.isNotBlank())
        require(generation > 0)
    }
}

/** 同一会话中的单次操作身份，避免旧的应用回执覆盖新修订。 */
data class OperationToken(val sessionId: SessionId, val sequence: Long) {
    init {
        require(sequence > 0)
    }
}

enum class Capability { Connect, ApplySavedRevision, TunCounters }

/** 未提供、暂未实现、超时与操作失败有各自语义，不使用空字符串作为未知。 */
enum class UnavailableReason {
    NoMeasurement, NotImplemented, TimedOut, EngineRejected, NoProfile, Busy,
    DifferentRunningProfile, NotRunning,
    PermissionDenied, ServiceRevoked, InvalidConfig, ProtectionFailed, AddressUnavailable,
    TunInstallFailed, ProtocolMismatch, ResultUnknown,
}

enum class DataFreshness { Fresh, Stale, Unavailable }

/** 测量值和缺测原因互斥；测得 0 与缺测 null 的语义不同。时间单位为单调毫秒。 */
data class Observation<T>(
    val value: T?,
    val observedAtMillis: Long?,
    val freshness: DataFreshness,
    val unavailableReason: UnavailableReason? = null,
) {
    init {
        require(
            if (value == null) {
                freshness == DataFreshness.Unavailable && unavailableReason != null
            } else {
                freshness != DataFreshness.Unavailable && observedAtMillis != null &&
                    unavailableReason == null
            }
        )
        require(observedAtMillis == null || observedAtMillis >= 0)
    }
}

/** 最后确认采用的保存版本；结果未知时 freshness=Stale，只能作为历史参考。 */
data class AppliedSessionSnapshot(
    val sessionId: SessionId,
    val saved: SavedRevision,
    val virtualIpv4: Observation<String>,
    val freshness: DataFreshness = DataFreshness.Fresh,
) {
    init { require(freshness != DataFreshness.Unavailable) }
}

enum class ConnectionPhase {
    Idle, Preparing, AwaitingPermission, Connecting, AwaitingAddress, PreparingTun,
    Running, Reconnecting, Stopping, OutcomeUnknown, Failed,
}
enum class OperationKind { Connect, Apply, Stop }

/** 操作失败和连接阶段独立；Apply 失败时仍可保持 Running 与旧 applied。 */
data class OperationFailure(val kind: OperationKind, val reason: UnavailableReason)

/** 控制器的原子快照；[applied] 的 freshness 区分当前已确认配置和结果未知时的历史。 */
data class SessionState(
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val sessionId: SessionId? = null,
    val requestedProfileId: LocalProfileId? = null,
    val applied: AppliedSessionSnapshot? = null,
    val pending: OperationKind? = null,
    val failure: OperationFailure? = null,
    val runtime: Observation<RuntimeSnapshot>? = null,
    val core: Observation<CoreSnapshot>? = null,
    val buildInfo: Observation<CoreBuildInfo>? = null,
    val traffic: List<TrafficPoint> = emptyList(),
    val lifecycle: List<LifecycleDiagnostic> = emptyList(),
    val coreDiagnostics: CoreDiagnostics? = null,
)

sealed interface OperationResult {
    data class Accepted(val token: OperationToken) : OperationResult
    data class Rejected(val reason: UnavailableReason) : OperationResult
    data object Unchanged : OperationResult
}

/** UI 只能发出显式会话动作；查看配置和保存配置不属于此接口。 */
interface SessionController {
    val state: StateFlow<SessionState>
    fun connect(saved: SavedRevision): OperationResult
    fun apply(saved: SavedRevision): OperationResult
    fun disconnect(): OperationResult
    /** 恢复最后确认运行的内容；不覆盖已保存修订、本地名称或编辑草稿。 */
    fun restoreRunning(): OperationResult = OperationResult.Rejected(UnavailableReason.NotImplemented)
}

/** 引擎命令携带操作归属；submit 返回不代表后台工作完成，必须等待匹配回执。 */
sealed interface EngineCommand {
    val token: OperationToken
    data class Start(override val token: OperationToken, val saved: SavedRevision, val protector: SocketProtector? = null) : EngineCommand
    data class Apply(override val token: OperationToken, val saved: SavedRevision) : EngineCommand
    data class Stop(override val token: OperationToken) : EngineCommand
    data class QueryRuntime(override val token: OperationToken) : EngineCommand
    /** PFD 仍由 Service 持有；此处只传借用 fd，超时不能关闭原 PFD。 */
    data class InstallTun(override val token: OperationToken, val attachmentId: AttachmentId, val fd: Int, val spec: VpnInterfaceSpec) : EngineCommand {
        init { require(attachmentId.sessionId == token.sessionId && fd >= 0) }
    }
    data class RemoveTun(override val token: OperationToken, val attachmentId: AttachmentId) : EngineCommand {
        init { require(attachmentId.sessionId == token.sessionId) }
    }
}

/** 失败时实际资源的已知状态；Unknown 不能按已停止或旧会话仍运行处理。 */
enum class FailureDisposition { Stopped, PreviousRunning, Unknown }

/** 带归属的回执；只有与控制器当前 pending 完全匹配的结果才可提交。 */
sealed interface EngineEvent {
    val token: OperationToken
    data class CoreStarted(override val token: OperationToken, val savedRevision: Long) : EngineEvent
    data class TunAttached(override val token: OperationToken, val installed: TunInstalled) : EngineEvent {
        init { require(installed.attachmentId.sessionId == token.sessionId) }
    }
    data class TunRemoved(override val token: OperationToken, val attachmentId: AttachmentId) : EngineEvent {
        init { require(attachmentId.sessionId == token.sessionId) }
    }
    data class CoreObserved(override val token: OperationToken, val snapshot: CoreSnapshot) : EngineEvent {
        init { require(snapshot.sessionId == token.sessionId) }
    }
    /** 整体准备完成回执；正式实现必须先确认核心、实际地址、TUN 安装和平台路由。 */
    data class Ready(override val token: OperationToken, val ipv4: Observation<String>) : EngineEvent {
        init { require(ipv4.freshness == DataFreshness.Fresh && ipv4.value?.let(::isIpv4Literal) == true) }
    }
    data class Progress(override val token: OperationToken, val phase: ConnectionPhase) : EngineEvent {
        init { require(phase in setOf(ConnectionPhase.Connecting, ConnectionPhase.AwaitingAddress, ConnectionPhase.PreparingTun)) }
    }
    data class Stopped(override val token: OperationToken, val confirmation: StopConfirmation) : EngineEvent {
        init { require(confirmation.sessionId == token.sessionId) }
    }
    data class Failed(
        override val token: OperationToken,
        val reason: UnavailableReason,
        val disposition: FailureDisposition,
    ) : EngineEvent
    data class OutcomeUnknown(override val token: OperationToken) : EngineEvent
    data class RuntimeObserved(override val token: OperationToken, val snapshot: RuntimeSnapshot) : EngineEvent {
        init { require(snapshot.sessionId == token.sessionId) }
    }
}

/** 可替换的领域引擎边界；demo 实现只在 demo source set 中装配。 */
interface CoreEngine {
    val capabilities: Set<Capability>
    val events: Flow<EngineEvent>
    /** 当前生命周期回执消费者数量；未提供实际计数的替身返回 null。 */
    val lifecycleSubscriberCount: Long? get() = null
    fun submit(command: EngineCommand)
    suspend fun buildInfo(): ConfirmationResult<CoreBuildInfo> = ConfirmationResult.Failed(CoreFailure(CoreErrorCode.NotImplemented))
    suspend fun validate(saved: SavedRevision): ConfirmationResult<Unit> = ConfirmationResult.Failed(CoreFailure(CoreErrorCode.NotImplemented))
    /** 仅在停止确认且 Service 已关闭 PFD 后释放 JNI 引用；失败时仍阻止再次启动。 */
    suspend fun releaseSession(token: OperationToken): ConfirmationResult<Unit> = ConfirmationResult.Confirmed(Unit)
    suspend fun diagnostics(): ConfirmationResult<CoreDiagnostics> = ConfirmationResult.Failed(CoreFailure(CoreErrorCode.NotImplemented))
}

/** 注入单调时钟，使 fixture 和测试不依赖墙钟时间。 */
fun interface MonotonicClock {
    fun nowMillis(): Long
}
