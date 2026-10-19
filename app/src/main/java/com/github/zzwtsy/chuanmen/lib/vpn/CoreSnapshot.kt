package com.github.zzwtsy.chuanmen.lib.vpn

/** 平台能力由 Service 提供；返回 true 必须已完成 VPN 保护和物理网络绑定。 */
fun interface SocketProtector {
    fun protect(fd: Int, sessionId: SessionId): Boolean
}

/** 构建身份来自本次加载的库，不能用 P00 报告或 Gradle 常量代替运行查询。 */
data class CoreBuildInfo(
    val protocol: Int,
    val coreRevision: String,
    val coreVersion: String,
    val bridgeVersion: String,
    val abi: String,
    val platform: String,
    val features: Set<String>,
)

data class CorePeer(val peerId: Long, val connections: Long)
data class CoreRoute(val peerId: Long, val ipv4: Ipv4Cidr?, val proxyCidrs: List<Ipv4Cidr>, val cost: Long, val version: String)
enum class TunBorrowState { Pending, Installed, Released, Failed }

/** 核心事实不等于 Android VPN 已准备；Service 还须核对实际 TUN 和平台路由。 */
data class CoreSnapshot(
    val sessionId: SessionId,
    val lastOperation: OperationToken,
    val savedRevision: Long,
    val status: RuntimeStatus,
    val ipv4: Ipv4Cidr?,
    val peers: List<CorePeer>,
    val routes: List<CoreRoute>,
    val udpNatType: Int?,
    val tcpNatType: Int?,
    val attachmentId: AttachmentId?,
    val attachmentState: TunBorrowState?,
    val counters: TunCounters?,
    val activeInstances: Long,
    val callbacksInFlight: Long,
    val jniGlobalRefs: Long,
    val protectionCalls: Long,
    val protectionFailures: Long,
    val runtimeThreads: Long? = null,
) {
    init {
        require(lastOperation.sessionId == sessionId && savedRevision > 0)
        require(attachmentId == null || attachmentId.sessionId == sessionId)
        require((attachmentId == null) == (attachmentState == null))
        require(listOf(activeInstances, callbacksInFlight, jniGlobalRefs, protectionCalls, protectionFailures).all { it >= 0 })
        require(runtimeThreads == null || runtimeThreads >= 0)
    }
}

/** 核心进程日志不能强归到当前会话；只暴露已脱敏的静态分类，不携带原始字段。 */
data class CoreDiagnostic(val elapsedMillis: Long, val scope: String, val level: String, val target: String, val code: String)
data class CoreDiagnostics(val records: List<CoreDiagnostic>, val dropped: Long, val capacity: Int)

/** 一秒趋势槽位；缺测为 null，累计计数仍保留 Long，速率仅供显示。 */
data class TrafficPoint(val observedAtMillis: Long, val txBytesPerSecond: Double?, val rxBytesPerSecond: Double?)

/** 生命周期收据的脱敏摘要；不保存配置、原始异常或业务包。 */
data class LifecycleDiagnostic(val elapsedMillis: Long, val token: OperationToken, val savedRevision: Long, val code: String)

/** Service 实际持有的资源和作用域任务，用于停止后的资源回归；不读取 UI 或进程外状态。 */
data class ServiceResourceCounts(val activeSessions: Int, val ownedTunDescriptors: Int, val lifecycleSubscribers: Long?, val scopeJobs: Int)
