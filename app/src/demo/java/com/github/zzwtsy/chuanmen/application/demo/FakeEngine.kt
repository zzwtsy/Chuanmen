package com.github.zzwtsy.chuanmen.application.demo

import com.github.zzwtsy.chuanmen.contracts.Capability
import com.github.zzwtsy.chuanmen.contracts.CoreEngine
import com.github.zzwtsy.chuanmen.contracts.DataFreshness
import com.github.zzwtsy.chuanmen.contracts.EngineCommand
import com.github.zzwtsy.chuanmen.contracts.EngineEvent
import com.github.zzwtsy.chuanmen.contracts.Ipv4Assignment
import com.github.zzwtsy.chuanmen.contracts.MonotonicClock
import com.github.zzwtsy.chuanmen.contracts.Observation
import com.github.zzwtsy.chuanmen.contracts.AttachmentId
import com.github.zzwtsy.chuanmen.contracts.FailureDisposition
import com.github.zzwtsy.chuanmen.contracts.Ipv4Cidr
import com.github.zzwtsy.chuanmen.contracts.RuntimeSnapshot
import com.github.zzwtsy.chuanmen.contracts.RuntimeStatus
import com.github.zzwtsy.chuanmen.contracts.SavedRevision
import com.github.zzwtsy.chuanmen.contracts.StopConfirmation
import com.github.zzwtsy.chuanmen.contracts.UnavailableReason
import com.github.zzwtsy.chuanmen.contracts.VpnInterfaceSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 可注入的事件源；只允许一个控制器消费，由调用方的作用域负责关闭生命周期。
 *
 * 保留停止后仍可能到达的旧事件，用于验证控制器的代次隔离，不模拟真实资源释放。
 */
class ScheduledEngineEvents(private val scope: CoroutineScope) {
    private val queue = Channel<EngineEvent>(Channel.UNLIMITED)
    val events = queue.receiveAsFlow()

    fun schedule(delayMillis: Long, event: () -> EngineEvent?) {
        scope.launch {
            delay(delayMillis)
            event()?.let { queue.send(it) }
        }
    }
}

/** 只解释固定脚本的模拟引擎；不创建 socket、TUN 或 JNI 句柄。 */
class FakeEngine(
    private val clock: MonotonicClock,
    private val source: ScheduledEngineEvents,
    private val script: EngineScript,
) : CoreEngine {
    override val capabilities = setOf(Capability.Connect, Capability.ApplySavedRevision)
    override val events = source.events
    private var starts = 0
    private var applies = 0
    private val commands = mutableListOf<EngineCommand>()
    private var active: Pair<EngineCommand, SavedRevision>? = null
    private val stopped = mutableSetOf<com.github.zzwtsy.chuanmen.contracts.SessionId>()
    val submittedCommands: List<EngineCommand> get() = commands.toList()

    override fun submit(command: EngineCommand) {
        commands += command
        if (command is EngineCommand.QueryRuntime) {
            val current = active?.takeIf { it.first.token.sessionId == command.token.sessionId }
            source.schedule(0) {
                val saved = current?.second
                val address = saved?.let { ipv4(it) }
                val spec = address?.let {
                    VpnInterfaceSpec(
                        listOf(Ipv4Cidr(it, 24)), listOf(Ipv4Cidr(it.substringBeforeLast('.') + ".0", 24)),
                        saved.vpnPolicy.dnsServers, saved.core.mtu,
                    )
                }
                EngineEvent.RuntimeObserved(command.token, RuntimeSnapshot(
                    command.token.sessionId, current?.first?.token,
                    if (current != null) RuntimeStatus.Running else RuntimeStatus.Unknown,
                    saved?.revision, current?.let { AttachmentId(command.token.sessionId, 1) },
                    if (spec != null) Observation(spec, clock.nowMillis(), DataFreshness.Fresh)
                    else Observation(null, null, DataFreshness.Unavailable, UnavailableReason.NoMeasurement),
                    Observation(null, null, DataFreshness.Unavailable, UnavailableReason.NotImplemented),
                ))
            }
            return
        }
        if (command is EngineCommand.Stop) {
            stopped += command.token.sessionId
            source.schedule(script.stopDelayMillis) {
                if (script.stopConfirms) {
                    if (active?.first?.token?.sessionId == command.token.sessionId) active = null
                    EngineEvent.Stopped(command.token, StopConfirmation(command.token.sessionId, drained = true))
                } else EngineEvent.OutcomeUnknown(command.token)
            }
            return
        }
        if (command is EngineCommand.InstallTun || command is EngineCommand.RemoveTun) {
            source.schedule(0) { EngineEvent.Failed(command.token, UnavailableReason.NotImplemented, FailureDisposition.Unknown) }
            return
        }
        val reply = when (command) {
            is EngineCommand.Start -> script.starts.elementAtOrElse(starts++) { script.starts.last() }
            is EngineCommand.Apply -> script.applies.elementAtOrElse(applies++) { script.applies.last() }
            is EngineCommand.Stop -> error("停止已单独处理")
            is EngineCommand.QueryRuntime -> error("查询已单独处理")
            is EngineCommand.InstallTun, is EngineCommand.RemoveTun -> error("原生 TUN 命令已单独处理")
        }
        if (reply is ScriptReply.Silent) return
        val saved = when (command) {
            is EngineCommand.Start -> command.saved
            is EngineCommand.Apply -> command.saved
            is EngineCommand.Stop -> error("停止已单独处理")
            is EngineCommand.QueryRuntime -> error("查询已单独处理")
            is EngineCommand.InstallTun, is EngineCommand.RemoveTun -> error("原生 TUN 命令已单独处理")
        }
        if (reply is ScriptReply.Ready) {
            reply.progress.forEach { progress ->
                source.schedule(progress.atMillis) { EngineEvent.Progress(command.token, progress.phase) }
            }
        }
        source.schedule(reply.delayMillis) {
            when (reply) {
                is ScriptReply.Ready -> {
                    if (command.token.sessionId !in stopped) active = command to saved
                    if (reply.deliverReceipt) EngineEvent.Ready(
                        command.token, Observation(ipv4(saved), clock.nowMillis(), DataFreshness.Fresh),
                    ) else null
                }
                is ScriptReply.Failed -> EngineEvent.Failed(
                    command.token, reply.reason,
                    reply.disposition ?: if (command is EngineCommand.Apply) FailureDisposition.PreviousRunning else FailureDisposition.Stopped,
                )
                is ScriptReply.Unknown -> EngineEvent.OutcomeUnknown(command.token)
                ScriptReply.Silent -> error("无回执脚本不应排队")
            }
        }
    }

    private fun ipv4(saved: SavedRevision): String = when (val assignment = saved.core.ipv4) {
        Ipv4Assignment.Automatic -> "10.42.0.1"
        is Ipv4Assignment.Static -> assignment.address
    }
}
