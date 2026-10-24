package com.github.zzwtsy.chuanmen.lib.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import com.github.zzwtsy.chuanmen.lib.profile.SavedRevision
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 应用进程的本地服务客户端，只转交意图和观察状态，不拥有核心或 PFD。
 * 绑定使用 applicationContext；Activity 重建仅替换授权入口，不结束 Service 会话。
 */
class VpnSessionClient(context: Context) : SessionController {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(SessionState())
    override val state: StateFlow<SessionState> = mutableState
    private var binder: NativeVpnService.ControlBinder? = null
    private var collecting: Job? = null
    private var bound = false
    private var queued: SavedRevision? = null
    private var awaitingPermission: SavedRevision? = null
    private var requestAuthorization: ((Intent) -> Unit)? = null
    private var authorizationOwner: Any? = null
    private var sequence = 0L

    fun attachAuthorization(owner: Any, request: (Intent) -> Unit) {
        authorizationOwner = owner
        requestAuthorization = request
        bind()
    }
    fun detachAuthorization(owner: Any) {
        if (authorizationOwner === owner) { authorizationOwner = null; requestAuthorization = null }
    }

    private fun bind() {
        if (bound) return
        bound = context.bindService(Intent(context, NativeVpnService::class.java).setAction(NativeVpnService.ACTION_CONTROL), connection, Context.BIND_AUTO_CREATE)
    }

    override fun connect(saved: SavedRevision): OperationResult {
        if (queued != null || awaitingPermission != null || state.value.phase in setOf(ConnectionPhase.Stopping, ConnectionPhase.OutcomeUnknown)) return OperationResult.Rejected(UnavailableReason.Busy)
        val authorization = VpnService.prepare(context)
        if (authorization != null) {
            val request = requestAuthorization ?: return OperationResult.Rejected(UnavailableReason.PermissionDenied)
            val token = OperationToken(SessionId(UUID.randomUUID().toString(), ++sequence), 1)
            awaitingPermission = saved
            mutableState.value = SessionState(ConnectionPhase.AwaitingPermission, token.sessionId, saved.profileId, pending = OperationKind.Connect)
            request(authorization)
            return OperationResult.Accepted(token)
        }
        return dispatchConnect(saved)
    }

    /** 授权返回时重新读取当前准备状态；启动中取消会清除待授权配置，迟到结果被忽略。 */
    fun authorizationResult(granted: Boolean) {
        val saved = awaitingPermission ?: return
        awaitingPermission = null
        if (granted && VpnService.prepare(context) == null) dispatchConnect(saved)
        else mutableState.value = SessionState(ConnectionPhase.Failed, requestedProfileId = saved.profileId,
            failure = OperationFailure(OperationKind.Connect, UnavailableReason.PermissionDenied))
    }

    private fun dispatchConnect(saved: SavedRevision): OperationResult {
        bind()
        try {
            val intent = Intent(context, NativeVpnService::class.java).setAction(NativeVpnService.ACTION_FOREGROUND)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        } catch (_: RuntimeException) { return rejectConnect(saved, UnavailableReason.EngineRejected) }
        binder?.let { control -> return control.connect(saved) }
        queued = saved
        val token = OperationToken(SessionId(UUID.randomUUID().toString(), ++sequence), 1)
        mutableState.value = SessionState(ConnectionPhase.Preparing, token.sessionId, saved.profileId, pending = OperationKind.Connect)
        return OperationResult.Accepted(token)
    }

    override fun apply(saved: SavedRevision): OperationResult = binder?.apply(saved) ?: OperationResult.Rejected(UnavailableReason.NotRunning)
    override fun query(): OperationResult = binder?.query() ?: OperationResult.Rejected(UnavailableReason.NotRunning)
    override suspend fun deleteProfile(id: com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId): com.github.zzwtsy.chuanmen.lib.profile.WriteResult<Unit> {
        if (queued?.profileId == id) queued = null
        if (awaitingPermission?.profileId == id) { awaitingPermission = null; mutableState.value = SessionState() }
        return binder?.deleteProfile(id) ?: com.github.zzwtsy.chuanmen.lib.profile.WriteResult.Failure(com.github.zzwtsy.chuanmen.lib.profile.WriteError.ReadUnavailable)
    }
    override fun restoreRunning(): OperationResult = binder?.restoreRunning() ?: OperationResult.Rejected(UnavailableReason.NotRunning)
    /** 诊断读取也经同 UID Binder 核对，客户端不直接查看或拥有核心资源。 */
    fun resources(): ServiceResourceCounts? = binder?.resources()
    suspend fun setDiagnosticDebug(enabled: Boolean): Boolean = binder?.setDiagnosticDebug(enabled) ?: false
    override fun disconnect(): OperationResult {
        awaitingPermission = null; queued = null
        val result = binder?.disconnect() ?: OperationResult.Unchanged
        if (binder == null || state.value.phase == ConnectionPhase.AwaitingPermission) mutableState.value = SessionState()
        return result
    }
    private fun rejectConnect(saved: SavedRevision, reason: UnavailableReason): OperationResult {
        mutableState.value = SessionState(ConnectionPhase.Failed, requestedProfileId = saved.profileId,
            failure = OperationFailure(OperationKind.Connect, reason))
        return OperationResult.Rejected(reason)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val control = service as? NativeVpnService.ControlBinder ?: run { bound = false; return }
            binder = control
            collecting?.cancel()
            collecting = scope.launch { control.state().collect { if (awaitingPermission == null && queued == null) mutableState.value = it } }
            queued?.let { saved ->
                queued = null
                val result = control.connect(saved)
                if (result is OperationResult.Rejected) rejectConnect(saved, result.reason)
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            binder = null; collecting?.cancel()
            mutableState.value = state.value.copy(phase = ConnectionPhase.OutcomeUnknown,
                applied = state.value.applied?.copy(freshness = DataFreshness.Stale),
                failure = OperationFailure(OperationKind.Stop, UnavailableReason.ResultUnknown))
        }
        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
            if (bound) context.unbindService(this)
            bound = false
        }
        override fun onNullBinding(name: ComponentName) { onBindingDied(name) }
    }
}
