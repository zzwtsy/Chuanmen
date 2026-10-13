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

/** 会话实际采用的保存版本；只由当前操作的成功回执建立。 */
data class AppliedSessionSnapshot(
    val sessionId: SessionId,
    val saved: SavedRevision,
    val virtualIpv4: Observation<String>,
)

enum class ConnectionPhase { Idle, Preparing, Connecting, Running, Stopping, Failed }
enum class OperationKind { Connect, Apply, Stop }

/** 操作失败和连接阶段独立；Apply 失败时仍可保持 Running 与旧 applied。 */
data class OperationFailure(val kind: OperationKind, val reason: UnavailableReason)

/** 控制器的原子快照；[applied] 只能表示尚未确认停止的实际会话。 */
data class SessionState(
    val phase: ConnectionPhase = ConnectionPhase.Idle,
    val sessionId: SessionId? = null,
    val requestedProfileId: LocalProfileId? = null,
    val applied: AppliedSessionSnapshot? = null,
    val pending: OperationKind? = null,
    val failure: OperationFailure? = null,
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
}

/** 引擎命令携带操作归属；P01 仅描述领域消息，不承诺生产 JNI/fd 生命周期。 */
sealed interface EngineCommand {
    val token: OperationToken
    data class Start(override val token: OperationToken, val saved: SavedRevision) : EngineCommand
    data class Apply(override val token: OperationToken, val saved: SavedRevision) : EngineCommand
    data class Stop(override val token: OperationToken) : EngineCommand
}

/** 带归属的回执；只有与控制器当前 pending 完全匹配的结果才可提交。 */
sealed interface EngineEvent {
    val token: OperationToken
    data class Ready(override val token: OperationToken, val ipv4: Observation<String>) : EngineEvent
    data class Stopped(override val token: OperationToken) : EngineEvent
    data class Failed(override val token: OperationToken, val reason: UnavailableReason) : EngineEvent
}

/** 可替换的领域引擎边界；demo 实现只在 demo source set 中装配。 */
interface CoreEngine {
    val capabilities: Set<Capability>
    val events: Flow<EngineEvent>
    fun submit(command: EngineCommand)
}

/** 注入单调时钟，使 fixture 和测试不依赖墙钟时间。 */
fun interface MonotonicClock {
    fun nowMillis(): Long
}
