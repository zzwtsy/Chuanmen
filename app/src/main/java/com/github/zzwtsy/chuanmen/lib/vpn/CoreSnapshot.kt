package com.github.zzwtsy.chuanmen.lib.vpn

import java.math.BigInteger

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

enum class MetricUnavailable { Missing, Unsupported, OutOfRange, Invalid }

/** 指标缺失只影响自身；Unsupported 表示桥未提供能力，Missing 表示本次没有样本。 */
data class CoreMetric<T>(val value: T?, val unavailable: MetricUnavailable? = null) {
    init { require((value == null) == (unavailable != null)) }
    companion object {
        fun <T> unsupported() = CoreMetric<T>(null, MetricUnavailable.Unsupported)
        fun <T> missing() = CoreMetric<T>(null, MetricUnavailable.Missing)
    }
}

/** 单条邻居连接的累计计数及核心 ping/pong RTT；不是中继远端的端到端统计。 */
data class CoreLink(
    val connectionId: String,
    val txBytes: CoreMetric<BigInteger>, val rxBytes: CoreMetric<BigInteger>,
    val txPackets: CoreMetric<BigInteger>, val rxPackets: CoreMetric<BigInteger>,
    val latencyMicros: CoreMetric<BigInteger>,
)

/** 网络归属来自核心握手；中继连接并不证明它当前正在转发流量。 */
enum class PeerNetworkRole { Member, Relay, Unknown }

data class CorePeer(
    val peerId: Long, val connections: Long, val links: List<CoreLink> = emptyList(),
    val networkRole: PeerNetworkRole = PeerNetworkRole.Unknown,
)
data class CoreRoute(
    val peerId: Long, val ipv4: Ipv4Cidr?, val proxyCidrs: List<Ipv4Cidr>, val cost: Long, val version: String,
    val hostname: CoreMetric<String> = CoreMetric.unsupported(),
    val nextHopPeerId: CoreMetric<Long> = CoreMetric.unsupported(),
    val udpNatType: CoreMetric<Int> = CoreMetric.unsupported(),
    val tcpNatType: CoreMetric<Int> = CoreMetric.unsupported(),
)
enum class TunBorrowState { Pending, Installed, Released, Failed, Unknown }

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
    val capabilities: Set<String> = emptySet(),
    val localPeerId: Long? = null,
    val localHostname: CoreMetric<String> = CoreMetric.unsupported(),
    val localVersion: CoreMetric<String> = CoreMetric.unsupported(),
    val countersUnavailable: MetricUnavailable? = if (counters == null) MetricUnavailable.Missing else null,
) {
    /** 每份快照只建一次索引，页面和探测共享事实，不增加采样任务。 */
    val peerIndex: SnapshotPeerIndex by lazy { SnapshotPeerIndex.from(this) }

    init {
        require(lastOperation.sessionId == sessionId && savedRevision > 0)
        require(attachmentId == null || attachmentId.sessionId == sessionId)
        require((attachmentId == null) == (attachmentState == null))
        require(listOf(activeInstances, callbacksInFlight, jniGlobalRefs, protectionCalls, protectionFailures).all { it >= 0 })
        require(runtimeThreads == null || runtimeThreads >= 0)
        require((counters == null) == (countersUnavailable != null))
    }
}

/** 核心进程日志不能强归到当前会话；只暴露已脱敏的静态分类，不携带原始字段。 */
data class CoreDiagnostic(val elapsedMillis: Long, val scope: String, val level: String, val target: String, val code: String,
    val fields: Map<String, String> = emptyMap())
data class CoreDiagnostics(val records: List<CoreDiagnostic>, val dropped: Long, val capacity: Int, val capturedElapsedMillis: Long? = null)

/** 一秒趋势槽位；缺测为 null，累计计数仍保留 Long，速率仅供显示。 */
data class TrafficPoint(val observedAtMillis: Long, val txBytesPerSecond: Double?, val rxBytesPerSecond: Double?)

/** 生命周期收据的脱敏摘要；不保存配置、原始异常或业务包。 */
data class LifecycleDiagnostic(val elapsedMillis: Long, val token: OperationToken, val savedRevision: Long, val code: String)

/** Service 实际持有的资源和作用域任务，用于停止后的资源回归；不读取 UI 或进程外状态。 */
data class ServiceResourceCounts(val activeSessions: Int, val ownedTunDescriptors: Int, val lifecycleSubscribers: Long?, val scopeJobs: Int)
