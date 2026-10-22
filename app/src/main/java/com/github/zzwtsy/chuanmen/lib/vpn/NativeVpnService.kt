package com.github.zzwtsy.chuanmen.lib.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import com.github.zzwtsy.chuanmen.lib.profile.ProfileRepositoryOwner
import com.github.zzwtsy.chuanmen.lib.profile.SavedRevision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** 唯一真实会话宿主；系统绑定和同 UID 应用控制绑定采用独立动作与方法检查。 */
open class NativeVpnService : VpnService(), TunPlatform {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var coordinator: VpnSessionCoordinator
    private lateinit var engine: CoreEngine
    private lateinit var connectivity: ConnectivityManager
    private val protectionLock = Any()
    private var physicalNetwork: Network? = null
    private var physicalLinkProperties: LinkProperties? = null
    private var protectionSession: SessionId? = null
    @Volatile private var destroyed = false
    private var foregroundStarted = false
    private val control = ControlBinder()

    protected open fun createEngine(scope: CoroutineScope): CoreEngine = NativeCoreEngine(scope)

    override fun onCreate() {
        super.onCreate()
        connectivity = getSystemService(ConnectivityManager::class.java)
        engine = createEngine(scope)
        coordinator = VpnSessionCoordinator(engine, this, scope, MonotonicClock(SystemClock::elapsedRealtime),
            onQuiescent = { if (destroyed) scope.cancel() })
        connectivity.registerNetworkCallback(NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback)
        refreshNetwork()
    }

    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_CONTROL) control else super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FOREGROUND -> if (coordinator.state.value.phase in setOf(ConnectionPhase.Idle, ConnectionPhase.Failed)) stopSelf()
                else if (!foreground()) coordinator.revoke()
            ACTION_STOP -> coordinator.disconnect()
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        denyProtection()
        scope.launch { coordinator.revoke() }
    }

    override fun onDestroy() {
        destroyed = true
        connectivity.unregisterNetworkCallback(networkCallback)
        denyProtection()
        // 停止未确认时保留作用域和 PFD；迟到确认仍有接收者。
        coordinator.revoke()
        super.onDestroy()
    }

    /** 仅同进程客户端使用；每个公开入口都核对 UID 与所属主线程。 */
    inner class ControlBinder internal constructor() : Binder() {
        private fun checkCaller() {
            if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("控制入口仅允许同 UID")
            check(Looper.myLooper() == Looper.getMainLooper()) { "本地控制入口须在主线程调用" }
        }
        fun state(): kotlinx.coroutines.flow.StateFlow<SessionState> { checkCaller(); return coordinator.state }
        fun resources(): ServiceResourceCounts {
            checkCaller()
            return ServiceResourceCounts(coordinator.activeSessionCount, coordinator.ownedTunCount,
                engine.lifecycleSubscriberCount, scope.coroutineContext[Job]!!.children.count())
        }
        private fun savedRevision(saved: SavedRevision): Boolean =
            (application as? ProfileRepositoryOwner)?.profiles?.findSaved(saved.profileId) == saved
        fun connect(saved: SavedRevision): OperationResult {
            checkCaller()
            val result = if (savedRevision(saved)) coordinator.connect(saved) else OperationResult.Rejected(UnavailableReason.InvalidConfig)
            if (result is OperationResult.Rejected && coordinator.state.value.phase in setOf(ConnectionPhase.Idle, ConnectionPhase.Failed)) leaveForeground()
            return result
        }
        fun apply(saved: SavedRevision): OperationResult {
            checkCaller()
            return if (savedRevision(saved)) coordinator.apply(saved) else OperationResult.Rejected(UnavailableReason.InvalidConfig)
        }
        fun disconnect(): OperationResult { checkCaller(); return coordinator.disconnect() }
        fun restoreRunning(): OperationResult { checkCaller(); return coordinator.restoreRunning() }
    }

    override val authorized: Boolean get() = !destroyed && prepare(this) == null
    override val networkAvailable: Boolean get() = synchronized(protectionLock) { physicalNetwork }?.let(::isPhysical) == true

    override fun foreground(): Boolean {
        if (!authorized) return false
        if (foregroundStarted) return true
        return try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "VPN 会话", NotificationManager.IMPORTANCE_LOW))
            val stop = PendingIntent.getService(this, 1, Intent(this, NativeVpnService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val open = PendingIntent.getActivity(this, 2, Intent(Intent.ACTION_MAIN)
                .setClassName(packageName, "com.github.zzwtsy.chuanmen.MainActivity")
                .addCategory(Intent.CATEGORY_LAUNCHER), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
            val notification = builder.setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle("串门 VPN 会话").setContentText("连接状态请在应用中查看")
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, "断开", stop).build()).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            else startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
            true
        } catch (_: RuntimeException) { false }
    }

    override fun leaveForeground() {
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    override fun allowProtection(sessionId: SessionId) { synchronized(protectionLock) { protectionSession = sessionId } }
    override fun denyProtection() { synchronized(protectionLock) { protectionSession = null } }

    override val protector = SocketProtector { fd, sessionId ->
        val network = synchronized(protectionLock) {
            physicalNetwork?.takeIf { protectionSession == sessionId && !destroyed }
        }
        if (network == null || !isPhysical(network)) false
        else try {
            if (!protect(fd)) false
            else {
                // 平台 I/O 不持有撤销入口的锁；主线程可立即撤销能力，原生停止另行等待在途回调。
                ParcelFileDescriptor.fromFd(fd).use { network.bindSocket(it.fileDescriptor) }
                val physical = isPhysical(network)
                synchronized(protectionLock) {
                    physical && !destroyed && protectionSession == sessionId && physicalNetwork == network
                }
            }
        } catch (_: Exception) { false }
    }

    override fun establish(spec: VpnInterfaceSpec): OwnedTun? {
        val network = synchronized(protectionLock) { physicalNetwork } ?: return null
        if (!authorized || !isPhysical(network)) return null
        val builder = Builder().setSession("串门").setMtu(spec.mtu).setBlocking(false)
            .setUnderlyingNetworks(arrayOf(network))
        spec.addresses.forEach { builder.addAddress(it.address, it.prefixLength) }
        spec.routes.forEach { builder.addRoute(it.address, it.prefixLength) }
        spec.dnsServers.forEach(builder::addDnsServer)
        // 不设置 IPv6 地址、路由、DNS 或 allowFamily，平台按 Builder 契约阻断 IPv6。
        val descriptor = builder.establish() ?: return null
        return object : OwnedTun {
            override val fd get() = descriptor.fd
            override fun close() = descriptor.close()
        }
    }

    private fun isPhysical(network: Network): Boolean = connectivity.getNetworkCapabilities(network)?.let {
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    } == true

    private fun refreshNetwork(lost: Network? = null) {
        if (destroyed) return
        val changed = synchronized(protectionLock) {
            val old = physicalNetwork
            val active = connectivity.activeNetwork?.takeIf { it != lost && isPhysical(it) }
            val next = active ?: old?.takeIf { it != lost && isPhysical(it) } ?: connectivity.allNetworks.firstOrNull { it != lost && isPhysical(it) }
            val properties = next?.let(connectivity::getLinkProperties)
            // 同一个 Network 的地址、路由或 DNS 变化也使旧绑定失效；onLost 不依赖可能迟更新的能力缓存。
            if (old != next || physicalLinkProperties != properties) {
                protectionSession = null
                physicalNetwork = next
                // ConnectivityManager 返回独立查询快照；不调用仅系统可用的复制构造器。
                physicalLinkProperties = properties
                true
            } else false
        }
        if (changed) coordinator.networkChanged()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { scope.launch { refreshNetwork() } }
        override fun onLost(network: Network) { scope.launch { refreshNetwork(network) } }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { scope.launch { refreshNetwork() } }
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) { scope.launch { refreshNetwork() } }
    }

    companion object {
        internal const val ACTION_CONTROL = "com.github.zzwtsy.chuanmen.vpn.CONTROL"
        internal const val ACTION_FOREGROUND = "com.github.zzwtsy.chuanmen.vpn.FOREGROUND"
        private const val ACTION_STOP = "com.github.zzwtsy.chuanmen.vpn.STOP"
        private const val CHANNEL = "chuanmen-vpn"
        private const val NOTIFICATION_ID = 2002
    }
}
