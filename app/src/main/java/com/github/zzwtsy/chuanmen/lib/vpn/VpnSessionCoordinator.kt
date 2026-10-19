package com.github.zzwtsy.chuanmen.lib.vpn

import com.github.zzwtsy.chuanmen.lib.profile.SavedRevision
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Service 的单线程事件处理器。公共动作和回执只在所属调度器上执行，耗时平台操作放后台。
 * 停止超时仍保留 Run、PFD 和终态订阅；只有借用终止、PFD 关闭、引用释放都确认后可重连。
 */
internal class VpnSessionCoordinator(
    private val engine: CoreEngine,
    private val platform: TunPlatform,
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val timeouts: SessionTimeouts = SessionTimeouts(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val onQuiescent: () -> Unit = {},
) : SessionController {
    private class Run(val id: SessionId, val saved: SavedRevision, val kind: OperationKind) {
        var sequence = 0L
        var attachmentSequence = 0L
        var pending: EngineCommand? = null
        var query: OperationToken? = null
        var tun: OwnedTun? = null
        var spec: VpnInterfaceSpec? = null
        var attachment: AttachmentId? = null
        var creatingTun = false
        var drained = false
        var releasing = false
        var replacement: VpnInterfaceSpec? = null
        fun token() = OperationToken(id, ++sequence)
    }
    private val mutableState = MutableStateFlow(SessionState())
    override val state: StateFlow<SessionState> = mutableState
    private var generation = 0L
    private var run: Run? = null
    private var desired: SavedRevision? = null
    private var lastConfirmed: SavedRevision? = null
    private var afterStopFailure: OperationFailure? = null
    private var deadline: Job? = null
    private var poll: Job? = null
    private var validating = false
    private val traffic = TunTrafficHistory()
    private val lifecycle = ArrayDeque<LifecycleDiagnostic>()
    private val startedAt = clock.nowMillis()
    private var diagnosticsReading = false
    private var processDiagnostics: CoreDiagnostics? = null
    val activeSessionCount: Int get() = if (run == null) 0 else 1
    val ownedTunCount: Int get() = if (run?.tun == null) 0 else 1

    init { scope.launch { engine.events.collect(::receive) } }

    override fun connect(saved: SavedRevision): OperationResult {
        if (run != null || validating) return OperationResult.Rejected(UnavailableReason.Busy)
        if (!platform.authorized) return OperationResult.Rejected(UnavailableReason.PermissionDenied)
        if (!platform.networkAvailable) return OperationResult.Rejected(UnavailableReason.NoMeasurement)
        if (!platform.foreground()) return OperationResult.Rejected(UnavailableReason.EngineRejected)
        desired = saved
        afterStopFailure = null
        return begin(saved, OperationKind.Connect)
    }

    private fun begin(saved: SavedRevision, kind: OperationKind): OperationResult {
        val current = Run(SessionId(UUID.randomUUID().toString(), ++generation), saved, kind)
        run = current
        traffic.reset()
        val token = current.token()
        platform.allowProtection(current.id)
        mutableState.value = SessionState(ConnectionPhase.Connecting, current.id, saved.profileId,
            pending = kind, applied = state.value.applied?.copy(freshness = DataFreshness.Stale),
            buildInfo = state.value.buildInfo, lifecycle = lifecycle.toList(), coreDiagnostics = processDiagnostics)
        dispatch(current, EngineCommand.Start(token, saved, platform.protector), timeouts.startMillis)
        scope.launch {
            val result = engine.buildInfo()
            if (run !== current) return@launch
            mutableState.value = state.value.copy(buildInfo = when (result) {
                is ConfirmationResult.Confirmed -> Observation(result.value, clock.nowMillis(), DataFreshness.Fresh)
                else -> Observation(null, null, DataFreshness.Unavailable, UnavailableReason.NoMeasurement)
            })
        }
        return OperationResult.Accepted(token)
    }

    override fun apply(saved: SavedRevision): OperationResult {
        val current = run ?: return OperationResult.Rejected(UnavailableReason.NotRunning)
        if (state.value.phase != ConnectionPhase.Running || validating) return OperationResult.Rejected(UnavailableReason.Busy)
        if (current.saved.profileId != saved.profileId) return OperationResult.Rejected(UnavailableReason.DifferentRunningProfile)
        if (current.saved.core == saved.core && current.saved.vpnPolicy == saved.vpnPolicy) return OperationResult.Unchanged
        val token = current.token()
        validating = true
        mutableState.value = state.value.copy(pending = OperationKind.Apply, failure = null)
        scope.launch {
            val valid = engine.validate(saved)
            if (run !== current || !validating || state.value.phase != ConnectionPhase.Running) return@launch
            validating = false
            if (valid is ConfirmationResult.Confirmed) {
                desired = saved
                stop(current, null, keepIntent = true)
            } else {
                mutableState.value = state.value.copy(pending = null,
                    failure = OperationFailure(OperationKind.Apply, UnavailableReason.InvalidConfig))
            }
        }
        return OperationResult.Accepted(token)
    }

    override fun disconnect(): OperationResult {
        desired = null
        validating = false
        val current = run ?: run {
            mutableState.value = SessionState()
            platform.leaveForeground()
            return OperationResult.Unchanged
        }
        if (current.pending is EngineCommand.Stop && state.value.phase == ConnectionPhase.Stopping) return OperationResult.Unchanged
        return stop(current, null, keepIntent = false)
    }

    fun revoke() {
        validating = false
        desired = null
        platform.denyProtection()
        run?.let { stop(it, OperationFailure(it.kind, UnavailableReason.ServiceRevoked), false) }
            ?: run { platform.leaveForeground(); onQuiescent() }
    }

    /** 外层网络变化先撤销旧保护能力；旧会话清理确认后才允许新代次使用新网络。 */
    fun networkChanged() {
        val current = run
        if (current != null && current.pending !is EngineCommand.Stop && !current.releasing) {
            stop(current, null, keepIntent = true)
        } else if (current == null && desired != null && platform.networkAvailable && platform.authorized) {
            begin(desired!!, OperationKind.Connect)
        }
    }

    /** 显式恢复上次确认运行内容，不写仓库、名称或编辑草稿。 */
    override fun restoreRunning(): OperationResult {
        val previous = lastConfirmed ?: return OperationResult.Rejected(UnavailableReason.NotRunning)
        return if (run == null) connect(previous) else apply(previous)
    }

    private fun dispatch(current: Run, command: EngineCommand, budget: Long) {
        current.pending = command
        record(current, command.token, when (command) {
            is EngineCommand.Start -> "command_start"
            is EngineCommand.Apply -> "command_apply"
            is EngineCommand.Stop -> "command_stop"
            is EngineCommand.InstallTun -> "command_install_tun"
            is EngineCommand.RemoveTun -> "command_remove_tun"
            is EngineCommand.QueryRuntime -> "command_query"
        })
        engine.submit(command)
        arm(current, budget)
    }

    private fun arm(current: Run, budget: Long) {
        deadline?.cancel()
        deadline = scope.launch {
            delay(budget)
            if (run !== current) return@launch
            if (current.pending is EngineCommand.Stop || current.releasing) unknown(current, query = false)
            else if (current.pending is EngineCommand.InstallTun || current.pending is EngineCommand.RemoveTun) unknown(current, query = true)
            else stop(current, OperationFailure(current.kind, UnavailableReason.TimedOut), false)
        }
    }

    private fun stop(current: Run, failure: OperationFailure?, keepIntent: Boolean): OperationResult {
        if (!keepIntent) desired = null
        validating = false
        afterStopFailure = failure
        platform.denyProtection()
        poll?.cancel(); poll = null
        current.query = null
        val token = current.token()
        mutableState.value = state.value.copy(phase = ConnectionPhase.Stopping, pending = OperationKind.Stop,
            applied = state.value.applied?.copy(freshness = DataFreshness.Stale), runtime = state.value.runtime?.asHistorical(),
            core = state.value.core?.let { if (it.value == null) it else it.copy(freshness = DataFreshness.Stale) })
        dispatch(current, EngineCommand.Stop(token), timeouts.stopMillis)
        return OperationResult.Accepted(token)
    }

    private fun receive(event: EngineEvent) {
        val current = run ?: return
        if (event.token.sessionId != current.id) return
        if (event !is EngineEvent.CoreObserved && event !is EngineEvent.RuntimeObserved) {
            record(current, event.token, when (event) {
                is EngineEvent.CoreStarted -> "receipt_core_started"
                is EngineEvent.TunAttached -> "receipt_tun_attached"
                is EngineEvent.TunRemoved -> "receipt_tun_removed"
                is EngineEvent.Stopped -> "receipt_stopped"
                is EngineEvent.Failed -> "receipt_failed"
                is EngineEvent.OutcomeUnknown -> "receipt_unknown"
                is EngineEvent.Progress -> "receipt_progress"
                is EngineEvent.Ready -> "receipt_ready"
                else -> "receipt_observed"
            })
        }
        // 首次停止可能早于 JNI 创建会话；启动的迟到终态到达后再次请求同一会话收束。
        if (event.token != current.pending?.token && current.pending is EngineCommand.Stop && !current.drained &&
            (event is EngineEvent.CoreStarted || event is EngineEvent.Failed)
        ) { stop(current, afterStopFailure, keepIntent = desired != null); return }
        if (event is EngineEvent.CoreObserved && current.query == event.token) {
            current.query = null
            observed(current, event.snapshot)
            return
        }
        if (event.token == current.query) {
            current.query = null
            if (state.value.phase != ConnectionPhase.Stopping) scheduleQuery(current)
            return
        }
        if (event.token != current.pending?.token) return
        when (event) {
            is EngineEvent.CoreStarted -> {
                if (current.pending !is EngineCommand.Start || event.savedRevision != current.saved.revision) return
                current.pending = null
                mutableState.value = state.value.copy(phase = ConnectionPhase.AwaitingAddress)
                arm(current, timeouts.addressMillis)
                query(current)
            }
            is EngineEvent.TunAttached -> {
                val pending = current.pending as? EngineCommand.InstallTun ?: return
                if (event.installed.attachmentId != pending.attachmentId || event.installed.spec != current.spec || current.tun == null) return
                current.pending = null
                deadline?.cancel()
                lastConfirmed = current.saved
                val address = current.spec!!.addresses.single().address
                mutableState.value = SessionState(ConnectionPhase.Running, current.id, current.saved.profileId,
                    AppliedSessionSnapshot(current.id, current.saved, Observation(address, clock.nowMillis(), DataFreshness.Fresh)),
                    buildInfo = state.value.buildInfo, lifecycle = lifecycle.toList(), coreDiagnostics = processDiagnostics)
                scheduleQuery(current, 0)
            }
            is EngineEvent.TunRemoved -> {
                if (current.pending !is EngineCommand.RemoveTun || event.attachmentId != current.attachment) return
                current.pending = null
                try { current.tun?.close() } catch (_: Exception) { unknown(current, false); return }
                current.tun = null; current.attachment = null
                establish(current, requireNotNull(current.replacement))
            }
            is EngineEvent.Stopped -> {
                if (current.pending !is EngineCommand.Stop) return
                current.pending = null
                current.drained = true
                finishStop(current)
            }
            is EngineEvent.OutcomeUnknown -> unknown(current, current.pending !is EngineCommand.Stop)
            is EngineEvent.Failed -> {
                if (current.pending is EngineCommand.Stop) unknown(current, false)
                else stop(current, OperationFailure(current.kind, event.reason), false)
            }
            // 整体 Ready 是 Service 的结论，不能接受核心边界替身伪造的平台就绪。
            else -> Unit
        }
    }

    private fun query(current: Run) {
        if (run !== current || current.query != null || current.pending is EngineCommand.Stop || current.releasing) return
        val token = current.token()
        current.query = token
        engine.submit(EngineCommand.QueryRuntime(token))
    }
    private fun scheduleQuery(current: Run, delayMillis: Long = 1_000) {
        poll?.cancel()
        poll = scope.launch { delay(delayMillis); query(current) }
    }

    private fun observed(current: Run, snapshot: CoreSnapshot) {
        if (snapshot.savedRevision != current.saved.revision) { unknown(current, false); return }
        mutableState.value = state.value.copy(core = Observation(snapshot, clock.nowMillis(), DataFreshness.Fresh))
        if (snapshot.protectionFailures > 0) {
            // 物理网络先失效、回调后到时仍保留运行意图；可用网络上的保护错误进入失败。
            val networkLost = !platform.networkAvailable
            stop(current, if (networkLost) null else OperationFailure(current.kind, UnavailableReason.ProtectionFailed), keepIntent = networkLost)
            return
        }
        val pending = current.pending
        if (pending is EngineCommand.Start && snapshot.lastOperation == pending.token && snapshot.status == RuntimeStatus.Running) {
            receive(EngineEvent.CoreStarted(pending.token, snapshot.savedRevision)); return
        }
        if (pending is EngineCommand.InstallTun && snapshot.status == RuntimeStatus.Running && snapshot.lastOperation == pending.token &&
            snapshot.attachmentId == pending.attachmentId && snapshot.attachmentState == TunBorrowState.Installed
        ) { receive(EngineEvent.TunAttached(pending.token, TunInstalled(pending.attachmentId, pending.spec))); return }
        if (pending is EngineCommand.RemoveTun && snapshot.status == RuntimeStatus.Running &&
            snapshot.attachmentState == null && snapshot.attachmentId == null) {
            receive(EngineEvent.TunRemoved(pending.token, pending.attachmentId)); return
        }
        if (state.value.phase == ConnectionPhase.AwaitingAddress && snapshot.ipv4 != null && snapshot.status == RuntimeStatus.Running) {
            try { establish(current, interfaceSpec(snapshot, current.saved)) }
            catch (_: IllegalArgumentException) { stop(current, OperationFailure(current.kind, UnavailableReason.AddressUnavailable), false) }
            return
        }
        if (state.value.phase == ConnectionPhase.Running) {
            if (snapshot.status != RuntimeStatus.Running || snapshot.ipv4 == null) {
                stop(current, OperationFailure(current.kind, UnavailableReason.AddressUnavailable), false); return
            }
            val nextSpec = try { interfaceSpec(snapshot, current.saved) } catch (_: IllegalArgumentException) {
                stop(current, OperationFailure(current.kind, UnavailableReason.AddressUnavailable), false); return
            }
            if (nextSpec != current.spec) {
                current.replacement = nextSpec
                mutableState.value = state.value.copy(phase = ConnectionPhase.Reconnecting,
                    applied = state.value.applied?.copy(freshness = DataFreshness.Stale))
                dispatch(current, EngineCommand.RemoveTun(current.token(), requireNotNull(current.attachment)), timeouts.tunMillis)
                return
            }
            if (snapshot.attachmentId != current.attachment || snapshot.attachmentState != TunBorrowState.Installed) {
                stop(current, OperationFailure(current.kind, UnavailableReason.TunInstallFailed), false); return
            }
            val runtime = RuntimeSnapshot(current.id, snapshot.lastOperation, RuntimeStatus.Running, snapshot.savedRevision,
                current.attachment, Observation(current.spec, clock.nowMillis(), DataFreshness.Fresh),
                snapshot.counters?.let { Observation(it, clock.nowMillis(), DataFreshness.Fresh) }
                    ?: Observation(null, null, DataFreshness.Unavailable, UnavailableReason.NoMeasurement))
            mutableState.value = state.value.copy(runtime = Observation(runtime, clock.nowMillis(), DataFreshness.Fresh),
                traffic = traffic.observe(requireNotNull(current.attachment), clock.nowMillis(), snapshot.counters))
            readDiagnostics(current)
        }
        scheduleQuery(current)
    }

    private fun establish(current: Run, spec: VpnInterfaceSpec) {
        if (current.creatingTun) return
        deadline?.cancel()
        current.creatingTun = true
        current.spec = spec
        val attachment = AttachmentId(current.id, ++current.attachmentSequence)
        current.attachment = attachment
        mutableState.value = state.value.copy(phase = ConnectionPhase.PreparingTun)
        arm(current, timeouts.tunMillis)
        scope.launch {
            val tun = withContext(io) { try { platform.establish(spec) } catch (_: Exception) { null } }
            current.creatingTun = false
            if (run !== current || current.pending is EngineCommand.Stop || current.drained) {
                try { tun?.close() } catch (_: Exception) { unknown(current, false); return@launch }
                if (run === current) finishStop(current)
                return@launch
            }
            if (tun == null) { stop(current, OperationFailure(current.kind, UnavailableReason.TunInstallFailed), false); return@launch }
            current.tun = tun
            dispatch(current, EngineCommand.InstallTun(current.token(), attachment, tun.fd, spec), timeouts.tunMillis)
        }
    }

    private fun finishStop(current: Run) {
        if (!current.drained || current.creatingTun || current.releasing) return
        try { current.tun?.close() } catch (_: Exception) { unknown(current, false); return }
        current.tun = null; current.attachment = null
        current.releasing = true
        arm(current, timeouts.stopMillis)
        val token = current.token()
        scope.launch {
            val released = engine.releaseSession(token)
            current.releasing = false
            if (run !== current) return@launch
            if (released !is ConfirmationResult.Confirmed) { unknown(current, false); return@launch }
            deadline?.cancel(); poll?.cancel()
            run = null
            val next = desired
            if (next != null && platform.networkAvailable && platform.authorized) {
                begin(next, if (next != current.saved) OperationKind.Apply else OperationKind.Connect)
            } else {
                val finalState = if (next != null) SessionState(ConnectionPhase.Reconnecting, requestedProfileId = next.profileId)
                    else afterStopFailure?.let { SessionState(ConnectionPhase.Failed, requestedProfileId = current.saved.profileId,
                        applied = state.value.applied?.copy(freshness = DataFreshness.Stale), failure = it) } ?: SessionState()
                mutableState.value = finalState.copy(buildInfo = state.value.buildInfo, lifecycle = lifecycle.toList(), coreDiagnostics = processDiagnostics)
                if (next == null) platform.leaveForeground()
                onQuiescent()
            }
        }
    }

    private fun unknown(current: Run, query: Boolean) {
        if (run !== current) return
        deadline?.cancel()
        mutableState.value = state.value.copy(phase = ConnectionPhase.OutcomeUnknown,
            applied = state.value.applied?.copy(freshness = DataFreshness.Stale), runtime = state.value.runtime?.asHistorical(),
            core = state.value.core?.let { if (it.value == null) it else it.copy(freshness = DataFreshness.Stale) },
            failure = OperationFailure(if (current.pending is EngineCommand.Stop) OperationKind.Stop else current.kind, UnavailableReason.ResultUnknown))
        if (query) {
            query(current)
            deadline = scope.launch { delay(timeouts.tunMillis); if (run === current && state.value.phase == ConnectionPhase.OutcomeUnknown) stop(current, OperationFailure(current.kind, UnavailableReason.ResultUnknown), false) }
        }
    }

    private fun record(current: Run, token: OperationToken, code: String) {
        if (lifecycle.size == 128) lifecycle.removeFirst()
        lifecycle.add(LifecycleDiagnostic((clock.nowMillis() - startedAt).coerceAtLeast(0), token, current.saved.revision, code))
        mutableState.value = state.value.copy(lifecycle = lifecycle.toList())
    }

    private fun readDiagnostics(current: Run) {
        if (diagnosticsReading) return
        diagnosticsReading = true
        scope.launch {
            try {
                val diagnostics = engine.diagnostics()
                if (diagnostics is ConfirmationResult.Confirmed) {
                    val value = diagnostics.value
                    processDiagnostics = value.copy(records = (processDiagnostics?.records.orEmpty() + value.records).takeLast(128))
                    if (run === current) mutableState.value = state.value.copy(coreDiagnostics = processDiagnostics)
                }
            } finally { diagnosticsReading = false }
        }
    }

    private fun Observation<RuntimeSnapshot>.asHistorical(): Observation<RuntimeSnapshot> =
        if (value != null) copy(freshness = DataFreshness.Stale) else this
}
