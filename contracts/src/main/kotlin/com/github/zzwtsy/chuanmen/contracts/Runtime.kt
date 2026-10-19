package com.github.zzwtsy.chuanmen.contracts

/** 原生 DTO 在 bridge 中核对版本；不兼容版本不能降级成连接成功。 */
object BridgeProtocol { const val VERSION = 1 }

/** 同一会话内每次 TUN 借用的身份；替换 TUN 必须增加 sequence。 */
data class AttachmentId(val sessionId: SessionId, val sequence: Long) {
    init { require(sequence > 0) }
}

/** 地址和路由使用明确前缀，格式检查不访问网络。 */
data class Ipv4Cidr(val address: String, val prefixLength: Int) {
    init {
        require(isIpv4Literal(address)) { "地址必须是 IPv4 字面量" }
        require(prefixLength in 0..32)
    }
}

/** 由核心实际地址和路由生成，不能以草稿或待应用配置充当已建立的接口。 */
data class VpnInterfaceSpec(
    val addresses: List<Ipv4Cidr>,
    val routes: List<Ipv4Cidr>,
    val dnsServers: List<String>,
    val mtu: Int,
    val ipv6: Ipv6Policy = Ipv6Policy.Block,
) {
    init {
        require(addresses.isNotEmpty())
        require(addresses.all { it.prefixLength > 0 })
        require(dnsServers.all(::isIpv4Literal))
        require(mtu in 576..9000)
    }
}

/** 成功 TUN I/O 的累计值；用整数传输，禁止经 Double 或速率反推累计计数。 */
data class TunCounters(val txBytes: Long, val rxBytes: Long, val txPackets: Long, val rxPackets: Long) {
    init { require(listOf(txBytes, rxBytes, txPackets, rxPackets).all { it >= 0 }) }
}

enum class RuntimeStatus { Starting, Running, Stopping, Stopped, Unknown }
enum class CoreErrorCode {
    InvalidConfig, ProtocolMismatch, ProtectorUnavailable, ProtectionFailed, NetworkBindingFailed,
    AddressUnavailable, AddressConflict, TunInstallFailed, QueueClosed, PartialInitialization,
    ServiceRevoked, NativeFailure, ConfirmationTimedOut, NotImplemented,
}

/** 无异常原文和配置回显的结构化错误，避免把 native 错误中的秘密带入日志。 */
data class CoreFailure(val code: CoreErrorCode, val retryable: Boolean = false)

/** 接受命令由 OperationResult 表达；此类型只表达后台工作的确认结果。 */
sealed interface ConfirmationResult<out T> {
    data class Confirmed<T>(val value: T) : ConfirmationResult<T>
    data class Failed(val failure: CoreFailure) : ConfirmationResult<Nothing>
    data class Unknown(val failure: CoreFailure) : ConfirmationResult<Nothing>
}

/** 安装确认说明实际安装而非入队；平台路由是否建立仍由 Service 独立确认。 */
data class TunInstalled(val attachmentId: AttachmentId, val spec: VpnInterfaceSpec)

/**
 * 只能在待安装队列、TUN I/O、会话任务及在途回调全部排空后构造的停止证明。
 * 未确认借用终止时返回 Unknown，不能构造部分成功的证明后关闭 Kotlin PFD。
 */
data class StopConfirmation(val sessionId: SessionId, val drained: Boolean) {
    init { require(drained) { "停止确认必须证明全部会话资源已排空" } }
}

/** 查询得到的运行事实；最后一次应用操作用于核对丢失的回执，缺测有独立原因。 */
data class RuntimeSnapshot(
    val sessionId: SessionId,
    val lastOperation: OperationToken?,
    val status: RuntimeStatus,
    val appliedRevision: Long?,
    val attachmentId: AttachmentId?,
    val interfaceSpec: Observation<VpnInterfaceSpec>,
    val tunCounters: Observation<TunCounters>,
) {
    init {
        require(lastOperation == null || lastOperation.sessionId == sessionId)
        require(attachmentId == null || attachmentId.sessionId == sessionId)
        require(appliedRevision == null || appliedRevision > 0)
        require(status != RuntimeStatus.Running || (appliedRevision != null && attachmentId != null &&
            interfaceSpec.freshness == DataFreshness.Fresh))
        require(status != RuntimeStatus.Stopped || attachmentId == null)
    }
}

/** 可注入的单调时间预算；等待超时表示结果未确认，不意味着工作没有执行。 */
data class SessionTimeouts(
    val startMillis: Long = 30_000,
    val addressMillis: Long = 30_000,
    val tunMillis: Long = 5_000,
    val stopMillis: Long = 10_000,
) {
    init { require(listOf(startMillis, addressMillis, tunMillis, stopMillis).all { it > 0 }) }
}
