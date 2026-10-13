package com.github.zzwtsy.chuanmen.application.demo

import com.github.zzwtsy.chuanmen.contracts.AppliedSessionSnapshot
import com.github.zzwtsy.chuanmen.contracts.ConnectionPhase
import com.github.zzwtsy.chuanmen.contracts.CoreEngine
import com.github.zzwtsy.chuanmen.contracts.EngineCommand
import com.github.zzwtsy.chuanmen.contracts.EngineEvent
import com.github.zzwtsy.chuanmen.contracts.OperationFailure
import com.github.zzwtsy.chuanmen.contracts.OperationKind
import com.github.zzwtsy.chuanmen.contracts.OperationResult
import com.github.zzwtsy.chuanmen.contracts.OperationToken
import com.github.zzwtsy.chuanmen.contracts.SavedRevision
import com.github.zzwtsy.chuanmen.contracts.SessionController
import com.github.zzwtsy.chuanmen.contracts.SessionId
import com.github.zzwtsy.chuanmen.contracts.SessionState
import com.github.zzwtsy.chuanmen.contracts.UnavailableReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * demo 的串行领域协调器：所有调用与回执在注入作用域的同一调度器上执行。
 *
 * 只接受当前 pending 的完整 token。此模型用于领域行为测试，不替代 P02 的服务、
 * TUN 安装、资源排空或生产重连实现。
 */
class DemoSessionController(
    private val engine: CoreEngine,
    private val scope: CoroutineScope,
    initial: SessionState = SessionState(),
    private val timeoutMillis: Long = 5000,
) : SessionController {
    private val mutableState = MutableStateFlow(initial)
    override val state = mutableState.asStateFlow()
    private var generation = initial.sessionId?.generation ?: 0
    private var sequence = 0L
    private var pending: EngineCommand? = null
    private var timeout: Job? = null

    init {
        require(timeoutMillis > 0)
        scope.launch { engine.events.collect(::accept) }
    }

    override fun connect(saved: SavedRevision): OperationResult {
        val current = state.value
        current.applied?.let {
            return if (it.saved.profileId == saved.profileId && current.phase == ConnectionPhase.Running) {
                OperationResult.Unchanged
            } else {
                OperationResult.Rejected(UnavailableReason.DifferentRunningProfile)
            }
        }
        if (pending != null) return OperationResult.Rejected(UnavailableReason.Busy)
        val key = SessionId("demo-session-${++generation}", generation)
        val command = EngineCommand.Start(token(key), saved)
        mutableState.value = SessionState(
            phase = ConnectionPhase.Preparing, sessionId = key,
            requestedProfileId = saved.profileId, pending = OperationKind.Connect,
        )
        begin(command)
        scope.launch {
            if (pending == command) {
                mutableState.value = state.value.copy(phase = ConnectionPhase.Connecting)
                engine.submit(command)
            }
        }
        return OperationResult.Accepted(command.token)
    }

    override fun apply(saved: SavedRevision): OperationResult {
        val current = state.value
        val applied = current.applied ?: return OperationResult.Rejected(UnavailableReason.NotRunning)
        if (pending != null || current.phase != ConnectionPhase.Running) {
            return OperationResult.Rejected(UnavailableReason.Busy)
        }
        if (applied.saved.profileId != saved.profileId) {
            return OperationResult.Rejected(UnavailableReason.DifferentRunningProfile)
        }
        if (applied.saved.revision == saved.revision) return OperationResult.Unchanged
        val command = EngineCommand.Apply(token(applied.sessionId), saved)
        mutableState.value = current.copy(pending = OperationKind.Apply, failure = null)
        begin(command)
        engine.submit(command)
        return OperationResult.Accepted(command.token)
    }

    override fun disconnect(): OperationResult {
        val current = state.value
        val key = current.sessionId ?: return OperationResult.Unchanged
        if (current.phase == ConnectionPhase.Idle) return OperationResult.Unchanged
        if (pending is EngineCommand.Stop) return OperationResult.Unchanged
        val command = EngineCommand.Stop(token(key))
        mutableState.value = current.copy(
            phase = ConnectionPhase.Stopping, pending = OperationKind.Stop, failure = null,
        )
        begin(command)
        engine.submit(command)
        return OperationResult.Accepted(command.token)
    }

    private fun token(key: SessionId) = OperationToken(key, ++sequence)

    private fun begin(command: EngineCommand) {
        timeout?.cancel()
        pending = command
        timeout = scope.launch {
            delay(timeoutMillis)
            if (pending?.token == command.token) {
                fail(command, UnavailableReason.TimedOut)
                if (command is EngineCommand.Start) engine.submit(EngineCommand.Stop(token(command.token.sessionId)))
            }
        }
    }

    private fun accept(event: EngineEvent) {
        val command = pending ?: return
        if (event.token != command.token) return
        when (event) {
            is EngineEvent.Ready -> {
                val saved = when (command) {
                    is EngineCommand.Start -> command.saved
                    is EngineCommand.Apply -> command.saved
                    is EngineCommand.Stop -> return
                }
                finish()
                mutableState.value = SessionState(
                    phase = ConnectionPhase.Running, sessionId = event.token.sessionId,
                    requestedProfileId = saved.profileId,
                    applied = AppliedSessionSnapshot(event.token.sessionId, saved, event.ipv4),
                )
            }
            is EngineEvent.Stopped -> {
                if (command !is EngineCommand.Stop) return
                finish()
                mutableState.value = SessionState()
            }
            is EngineEvent.Failed -> fail(command, event.reason)
        }
    }

    private fun fail(command: EngineCommand, reason: UnavailableReason) {
        val kind = when (command) {
            is EngineCommand.Start -> OperationKind.Connect
            is EngineCommand.Apply -> OperationKind.Apply
            is EngineCommand.Stop -> OperationKind.Stop
        }
        finish()
        mutableState.value = state.value.copy(
            phase = if (command is EngineCommand.Apply) ConnectionPhase.Running else ConnectionPhase.Failed,
            pending = null, failure = OperationFailure(kind, reason),
        )
    }

    private fun finish() {
        pending = null
        timeout?.cancel()
        timeout = null
    }
}
