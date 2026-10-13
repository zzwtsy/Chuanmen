package com.github.zzwtsy.chuanmen.application.demo

import com.github.zzwtsy.chuanmen.contracts.Capability
import com.github.zzwtsy.chuanmen.contracts.CoreEngine
import com.github.zzwtsy.chuanmen.contracts.DataFreshness
import com.github.zzwtsy.chuanmen.contracts.EngineCommand
import com.github.zzwtsy.chuanmen.contracts.EngineEvent
import com.github.zzwtsy.chuanmen.contracts.Ipv4Assignment
import com.github.zzwtsy.chuanmen.contracts.MonotonicClock
import com.github.zzwtsy.chuanmen.contracts.Observation
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

    fun schedule(delayMillis: Long, event: () -> EngineEvent) {
        scope.launch {
            delay(delayMillis)
            queue.send(event())
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
    val submittedCommands: List<EngineCommand> get() = commands.toList()

    override fun submit(command: EngineCommand) {
        commands += command
        if (command is EngineCommand.Stop) {
            source.schedule(script.stopDelayMillis) { EngineEvent.Stopped(command.token) }
            return
        }
        val reply = when (command) {
            is EngineCommand.Start -> script.starts.elementAtOrElse(starts++) { script.starts.last() }
            is EngineCommand.Apply -> script.applies.elementAtOrElse(applies++) { script.applies.last() }
            is EngineCommand.Stop -> error("停止已单独处理")
        }
        if (reply is ScriptReply.Silent) return
        val saved = when (command) {
            is EngineCommand.Start -> command.saved
            is EngineCommand.Apply -> command.saved
            is EngineCommand.Stop -> error("停止已单独处理")
        }
        val ipv4 = when (val assignment = saved.core.ipv4) {
            Ipv4Assignment.Automatic -> "10.42.0.1"
            is Ipv4Assignment.Static -> assignment.address
        }
        source.schedule(reply.delayMillis) {
            when (reply) {
                is ScriptReply.Ready -> EngineEvent.Ready(
                    command.token, Observation(ipv4, clock.nowMillis(), DataFreshness.Fresh),
                )
                is ScriptReply.Failed -> EngineEvent.Failed(command.token, reply.reason)
                ScriptReply.Silent -> error("无回执脚本不应排队")
            }
        }
    }
}
