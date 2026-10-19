package com.github.zzwtsy.chuanmen.application.demo

import com.github.zzwtsy.chuanmen.contracts.AndroidVpnPolicy
import com.github.zzwtsy.chuanmen.contracts.AppliedSessionSnapshot
import com.github.zzwtsy.chuanmen.contracts.ConnectionPhase
import com.github.zzwtsy.chuanmen.contracts.CoreConfig
import com.github.zzwtsy.chuanmen.contracts.CoreInstanceId
import com.github.zzwtsy.chuanmen.contracts.DataFreshness
import com.github.zzwtsy.chuanmen.contracts.Ipv4Assignment
import com.github.zzwtsy.chuanmen.contracts.LocalProfileId
import com.github.zzwtsy.chuanmen.contracts.Observation
import com.github.zzwtsy.chuanmen.contracts.OperationFailure
import com.github.zzwtsy.chuanmen.contracts.OperationKind
import com.github.zzwtsy.chuanmen.contracts.ProfileSummary
import com.github.zzwtsy.chuanmen.contracts.SavedRevision
import com.github.zzwtsy.chuanmen.contracts.SessionId
import com.github.zzwtsy.chuanmen.contracts.SessionState
import com.github.zzwtsy.chuanmen.contracts.UnavailableReason
import com.github.zzwtsy.chuanmen.contracts.FailureDisposition

/** 每条脚本的延时由注入作用域的调度器执行，Silent 用于精确复现超时。 */
sealed interface ScriptReply {
    val delayMillis: Long
    data class Ready(
        override val delayMillis: Long = 500,
        val progress: List<ScriptProgress> = emptyList(),
        val deliverReceipt: Boolean = true,
    ) : ScriptReply {
        init { require(progress.all { it.atMillis in 0 until delayMillis }) }
    }
    data class Failed(
        val reason: UnavailableReason = UnavailableReason.EngineRejected,
        override val delayMillis: Long = 500,
        val disposition: FailureDisposition? = null,
    ) : ScriptReply
    data object Silent : ScriptReply { override val delayMillis = 0L }
    data class Unknown(override val delayMillis: Long = 500) : ScriptReply
}

/** 从命令提交起计算的进度时间，最终 Ready 仍必须等待独立完成回执。 */
data class ScriptProgress(val atMillis: Long, val phase: ConnectionPhase)

/** 固定顺序的事件脚本；用完后重复最后一项，不依赖随机数或公网。 */
data class EngineScript(
    val starts: List<ScriptReply> = listOf(ScriptReply.Ready()),
    val applies: List<ScriptReply> = listOf(ScriptReply.Ready()),
    val stopDelayMillis: Long = 100,
    val stopConfirms: Boolean = true,
) {
    init {
        require(starts.isNotEmpty() && applies.isNotEmpty())
        require((starts + applies).all { it.delayMillis >= 0 } && stopDelayMillis >= 0)
    }
}

/** UI 的固定模拟样本；null 表示无采样，0.0 表示测得零速率。 */
data class DemoReadings(
    val txBytesPerSecond: List<Double?>,
    val rxBytesPerSecond: List<Double?>,
    val logs: List<String>,
)

/** 同一具名 fixture 同时供 demo、Compose Preview 和 JVM 行为测试消费。 */
data class DemoScenario(
    val name: String,
    val profiles: List<ProfileSummary>,
    val initialSession: SessionState = SessionState(),
    val script: EngineScript = EngineScript(),
    val readings: DemoReadings = DemoFixtures.fixedReadings,
)

object DemoFixtures {
    const val fixtureTimeMillis = 1000L
    val profileA = LocalProfileId("00000000-0000-4000-8000-000000000001")
    val profileB = LocalProfileId("00000000-0000-4000-8000-000000000002")
    val savedA = SavedRevision(
        profileA, 1,
        CoreConfig(CoreInstanceId("demo-instance-a"), "fixture-network-a", "fixture-phone"),
        AndroidVpnPolicy(),
    )
    val savedB = SavedRevision(
        profileB, 1,
        CoreConfig(
            CoreInstanceId("demo-instance-b"), "fixture-network-b", "fixture-tablet",
            Ipv4Assignment.Static("10.42.1.8"),
        ),
        AndroidVpnPolicy(),
    )
    val profiles = listOf(ProfileSummary(savedA, "演示网络 A"), ProfileSummary(savedB, "演示网络 B"))
    val fixedReadings = DemoReadings(
        listOf(null, 0.0, 120.0, 80.0, null, 0.0, 32.0),
        listOf(null, 0.0, 240.0, 160.0, null, 0.0, 64.0),
        listOf("App · DEMO_READY · 模拟数据已载入", "EasyTier · DEMO_FIXTURE · 固定核心日志样本"),
    )
    private val runningA = SessionState(
        phase = ConnectionPhase.Running,
        sessionId = SessionId("demo-session-1", 1),
        requestedProfileId = profileA,
        applied = AppliedSessionSnapshot(
            SessionId("demo-session-1", 1), savedA,
            Observation("10.42.0.1", fixtureTimeMillis, DataFreshness.Fresh),
        ),
    )
    val emptyNetwork = DemoScenario("空网络", emptyList())
    val disconnected = DemoScenario("未连接", profiles)
    val connected = DemoScenario("已连接 A", profiles, runningA)
    val connectionFailure = disconnected.copy(
        name = "连接失败", script = EngineScript(starts = listOf(ScriptReply.Failed())),
        initialSession = SessionState(
            phase = ConnectionPhase.Failed, sessionId = SessionId("demo-session-1", 1),
            requestedProfileId = profileA,
            failure = OperationFailure(OperationKind.Connect, UnavailableReason.EngineRejected),
        ),
    )
    val applyFailure = connected.copy(
        name = "应用失败", script = EngineScript(applies = listOf(ScriptReply.Failed())),
        profiles = profiles.map { profile ->
            if (profile.saved.profileId == profileA) {
                profile.copy(saved = savedA.copy(revision = 2, core = savedA.core.copy(mtu = 1400)))
            } else profile
        },
        initialSession = runningA.copy(failure = OperationFailure(OperationKind.Apply, UnavailableReason.EngineRejected)),
    )
    val noSamples = connected.copy(
        name = "无采样", readings = fixedReadings.copy(
            txBytesPerSecond = listOf(null), rxBytesPerSecond = listOf(null),
        ),
    )
    val all = listOf(emptyNetwork, disconnected, connected, connectionFailure, applyFailure, noSamples)

    /** P02 的阶段脚本用于契约回归，不表示 demo 获得了真实 TUN 或授权能力。 */
    val stagedConnection = disconnected.copy(
        name = "P02 分阶段连接",
        script = EngineScript(starts = listOf(ScriptReply.Ready(
            500, listOf(
                ScriptProgress(100, ConnectionPhase.AwaitingAddress),
                ScriptProgress(300, ConnectionPhase.PreparingTun),
            ),
        ))),
    )
}
