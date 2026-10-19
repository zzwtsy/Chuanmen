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
import com.github.zzwtsy.chuanmen.contracts.DataFreshness
import com.github.zzwtsy.chuanmen.contracts.FailureDisposition
import com.github.zzwtsy.chuanmen.contracts.MonotonicClock
import com.github.zzwtsy.chuanmen.contracts.Observation
import com.github.zzwtsy.chuanmen.contracts.RuntimeStatus
import com.github.zzwtsy.chuanmen.contracts.SessionTimeouts
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
    private val clock: MonotonicClock = MonotonicClock { (System.nanoTime() / 1_000_000).coerceAtLeast(0) },
    private val timeouts: SessionTimeouts = SessionTimeouts(timeoutMillis, timeoutMillis, timeoutMillis, timeoutMillis),
) : SessionController {
    private val mutableState = MutableStateFlow(initial)
    override val state = mutableState.asStateFlow()
    private var generation = initial.sessionId?.generation ?: 0
    private var sequence = 0L
    private var pending: EngineCommand? = null
    private var timeout: Job? = null
    private var failureAfterStop: OperationFailure? = null
    private var queryToken: OperationToken? = null

    init {
        require(timeoutMillis > 0)
        scope.launch { engine.events.collect(::accept) }
    }

    override fun connect(saved: SavedRevision): OperationResult {
        val current = state.value
        if (pending != null || current.phase == ConnectionPhase.OutcomeUnknown) {
            return OperationResult.Rejected(UnavailableReason.Busy)
        }
        current.applied?.let {
            return if (it.saved.profileId == saved.profileId && current.phase == ConnectionPhase.Running) {
                OperationResult.Unchanged
            } else {
                OperationResult.Rejected(UnavailableReason.DifferentRunningProfile)
            }
        }
        val key = SessionId("demo-session-${++generation}", generation)
        val command = EngineCommand.Start(token(key), saved)
        mutableState.value = SessionState(
            phase = ConnectionPhase.Preparing, sessionId = key,
            requestedProfileId = saved.profileId, pending = OperationKind.Connect,
        )
        failureAfterStop = null
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
        failureAfterStop = null
        return stop(key)
    }

    private fun stop(key: SessionId): OperationResult {
        val command = EngineCommand.Stop(token(key))
        mutableState.value = state.value.copy(
            phase = ConnectionPhase.Stopping, pending = OperationKind.Stop, failure = failureAfterStop,
        )
        begin(command)
        engine.submit(command)
        return OperationResult.Accepted(command.token)
    }

    private fun token(key: SessionId) = OperationToken(key, ++sequence)

    private fun begin(command: EngineCommand) {
        queryToken = null
        pending = command
        armTimeout(command, if (command is EngineCommand.Stop) timeouts.stopMillis else timeouts.startMillis)
    }

    private fun armTimeout(command: EngineCommand, budgetMillis: Long) {
        timeout?.cancel()
        timeout = scope.launch {
            delay(budgetMillis)
            if (pending?.token == command.token) {
                if (command is EngineCommand.Start) {
                    failureAfterStop = OperationFailure(OperationKind.Connect, UnavailableReason.TimedOut)
                    stop(command.token.sessionId)
                } else unknown(command, UnavailableReason.TimedOut)
            }
        }
    }

    private fun accept(event: EngineEvent) {
        if (event is EngineEvent.RuntimeObserved) {
            if (event.token != queryToken || event.token.sessionId != state.value.sessionId) return
            queryToken = null
            mutableState.value = state.value.copy(
                runtime = Observation(event.snapshot, clock.nowMillis(), DataFreshness.Fresh),
            )
            // 只有实际已应用操作与修订都匹配，查询才可替代丢失的成功回执。
            val command = pending
            val saved = when (command) {
                is EngineCommand.Start -> command.saved
                is EngineCommand.Apply -> command.saved
                else -> null
            }
            val snapshot = event.snapshot
            if (command != null && saved != null && snapshot.status == RuntimeStatus.Running &&
                snapshot.lastOperation == command.token && snapshot.appliedRevision == saved.revision
            ) {
                val spec = snapshot.interfaceSpec.value ?: return
                accept(EngineEvent.Ready(command.token, Observation(
                    spec.addresses.first().address, snapshot.interfaceSpec.observedAtMillis, DataFreshness.Fresh,
                )))
            }
            return
        }
        val command = pending ?: return
        if (event.token != command.token) return
        when (event) {
            is EngineEvent.Ready -> {
                val saved = when (command) {
                    is EngineCommand.Start -> command.saved
                    is EngineCommand.Apply -> command.saved
                    is EngineCommand.Stop -> return
                    is EngineCommand.QueryRuntime -> return
                    is EngineCommand.InstallTun, is EngineCommand.RemoveTun -> return
                }
                finish()
                mutableState.value = SessionState(
                    phase = ConnectionPhase.Running, sessionId = event.token.sessionId,
                    requestedProfileId = saved.profileId,
                    applied = AppliedSessionSnapshot(event.token.sessionId, saved, event.ipv4),
                    runtime = state.value.runtime?.takeIf { it.value?.lastOperation == event.token },
                )
            }
            is EngineEvent.Stopped -> {
                if (command !is EngineCommand.Stop) return
                finish()
                mutableState.value = failureAfterStop?.let {
                    SessionState(phase = ConnectionPhase.Failed, requestedProfileId = state.value.requestedProfileId, failure = it)
                } ?: SessionState()
                failureAfterStop = null
            }
            is EngineEvent.Failed -> {
                if (event.disposition == FailureDisposition.Unknown || command is EngineCommand.Stop ||
                    (command is EngineCommand.Start && event.disposition == FailureDisposition.PreviousRunning)
                ) unknown(command, event.reason)
                else fail(command, event.reason, event.disposition)
            }
            is EngineEvent.Progress -> {
                if (command is EngineCommand.Start && state.value.phase != ConnectionPhase.OutcomeUnknown &&
                    event.phase.ordinal > state.value.phase.ordinal
                ) {
                    mutableState.value = state.value.copy(phase = event.phase)
                    armTimeout(command, when (event.phase) {
                        ConnectionPhase.AwaitingAddress -> timeouts.addressMillis
                        ConnectionPhase.PreparingTun -> timeouts.tunMillis
                        else -> timeouts.startMillis
                    })
                }
            }
            is EngineEvent.OutcomeUnknown -> unknown(command, UnavailableReason.ResultUnknown)
            is EngineEvent.RuntimeObserved, is EngineEvent.CoreStarted, is EngineEvent.CoreObserved,
            is EngineEvent.TunAttached, is EngineEvent.TunRemoved -> Unit
        }
    }

    private fun kind(command: EngineCommand): OperationKind = when (command) {
        is EngineCommand.Start -> OperationKind.Connect
        is EngineCommand.Apply -> OperationKind.Apply
        is EngineCommand.Stop -> OperationKind.Stop
        is EngineCommand.QueryRuntime -> error("查询不属于变更操作")
        is EngineCommand.InstallTun, is EngineCommand.RemoveTun -> error("demo 不处理原生 TUN 操作")
    }

    private fun unknown(command: EngineCommand, reason: UnavailableReason) {
        timeout?.cancel()
        timeout = null
        // 保留 pending 和终态订阅，迟到的停止确认仍可结束借用；历史快照不能作为当前事实。
        mutableState.value = state.value.copy(
            phase = ConnectionPhase.OutcomeUnknown,
            failure = OperationFailure(kind(command), reason),
            applied = state.value.applied?.let { it.copy(
                freshness = DataFreshness.Stale,
                virtualIpv4 = it.virtualIpv4.value?.let { _ -> it.virtualIpv4.copy(freshness = DataFreshness.Stale) }
                    ?: it.virtualIpv4,
            ) },
            runtime = state.value.runtime?.let {
                if (it.value != null) it.copy(freshness = DataFreshness.Stale) else it
            },
        )
        if (queryToken == null) {
            queryToken = token(command.token.sessionId)
            engine.submit(EngineCommand.QueryRuntime(queryToken!!))
        }
    }

    private fun fail(command: EngineCommand, reason: UnavailableReason, disposition: FailureDisposition) {
        finish()
        mutableState.value = state.value.copy(
            phase = if (disposition == FailureDisposition.PreviousRunning) ConnectionPhase.Running else ConnectionPhase.Failed,
            applied = if (disposition == FailureDisposition.PreviousRunning) {
                state.value.applied?.copy(freshness = DataFreshness.Fresh)
            } else null,
            pending = null, failure = OperationFailure(kind(command), reason),
        )
    }

    private fun finish() {
        pending = null
        timeout?.cancel()
        timeout = null
        queryToken = null
    }
}
