package com.github.zzwtsy.chuanmen.lib.vpn

import java.io.Closeable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class ProbeTarget(val sessionId: SessionId, val attachmentId: AttachmentId, val peerId: Long, val ipv4: String) {
    init { require(attachmentId.sessionId == sessionId && peerId >= 0 && com.github.zzwtsy.chuanmen.utils.isIpv4Literal(ipv4)) }
}
enum class ProbeStatus { Running, Complete, Cancelled, Unavailable }
enum class ProbeUnavailable { NoFreshSession, RouteUnavailable, PermissionOrUnsupported, SendFailed, TransportFailed }
enum class ProbeStart { Started, Busy, Unavailable }
class ProbeUnavailableException(val reason: ProbeUnavailable) : Exception(reason.name)

/** sent 只统计发送成功的请求；取消保留部分统计，sent=0 时不计算丢包率。 */
data class ProbeResult(
    val taskId: Long, val target: ProbeTarget, val startedAtMillis: Long,
    val sent: Int = 0, val rttMillis: List<Long> = emptyList(),
    val status: ProbeStatus = ProbeStatus.Running, val unavailable: ProbeUnavailable? = null,
) {
    val received: Int get() = rttMillis.size
    val loss: Double? get() = if (sent == 0) null else (sent - received).toDouble() / sent
}

data class EchoReply(val target: String, val identifier: Int, val sequence: Int, val nonce: Long)

/** 后台 ICMP datagram 适配；关闭必须能打断接收，适配器不拥有任务或结果。 */
interface ProbeTransport : Closeable {
    val identifier: Int
    suspend fun send(sequence: Int, nonce: Long)
    suspend fun receive(timeoutMillis: Long): EchoReply?
}
fun interface ProbeTransportFactory { fun open(target: ProbeTarget): ProbeTransport }

/** 全进程唯一探测任务；会话变旧、目标消失或 attachment 变化时主动关闭 socket。 */
class ConnectionProbe(
    private val scope: CoroutineScope,
    private val sessions: StateFlow<SessionState>,
    private val factory: ProbeTransportFactory,
    private val clock: MonotonicClock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private class Active(val id: Long, val target: ProbeTarget) {
        var socket: ProbeTransport? = null
        var job: Job? = null
    }
    private val lock = Any()
    private var sequence = 0L
    private var active: Active? = null
    private val mutableResult = MutableStateFlow<ProbeResult?>(null)
    val result: StateFlow<ProbeResult?> = mutableResult

    init { scope.launch { sessions.collect { session -> synchronized(lock) {
        active?.takeIf { !eligible(session, it.target) }?.let { cancelLocked(it) }
    } } } }

    fun start(target: ProbeTarget): ProbeStart = synchronized(lock) {
        if (active != null) return ProbeStart.Busy
        val id = ++sequence
        if (!eligible(sessions.value, target)) {
            mutableResult.value = ProbeResult(id, target, clock.nowMillis(), status = ProbeStatus.Unavailable,
                unavailable = ProbeUnavailable.NoFreshSession)
            return ProbeStart.Unavailable
        }
        val task = Active(id, target)
        active = task
        mutableResult.value = ProbeResult(id, target, clock.nowMillis())
        task.job = scope.launch(io, start = CoroutineStart.LAZY) { execute(task) }
        task.job!!.start()
        ProbeStart.Started
    }

    fun cancel(target: ProbeTarget? = null, taskId: Long? = null) = synchronized(lock) {
        active?.takeIf { (target == null || it.target == target) && (taskId == null || it.id == taskId) }?.let(::cancelLocked)
        Unit
    }

    private fun cancelLocked(task: Active) {
        update(task) { it.copy(status = ProbeStatus.Cancelled) }
        // 先关闭阻塞接收，再取消协程；finally 取不到句柄，避免二次关闭。
        task.socket?.also { task.socket = null; runCatching { it.close() } }
        task.job?.cancel()
        if (active === task) active = null
    }

    private suspend fun execute(task: Active) {
        var failure = ProbeUnavailable.PermissionOrUnsupported
        try {
            val socket = factory.open(task.target)
            synchronized(lock) {
                if (active !== task) { socket.close(); return }
                task.socket = socket
            }
            for (index in 1..4) {
                currentCoroutineContext().ensureActive()
                failure = ProbeUnavailable.SendFailed
                val start = clock.nowMillis()
                socket.send(index, task.id)
                update(task) { it.copy(sent = it.sent + 1) }
                failure = ProbeUnavailable.TransportFailed
                val deadline = start + 1000
                while (clock.nowMillis() < deadline) {
                    currentCoroutineContext().ensureActive()
                    val reply = socket.receive(deadline - clock.nowMillis()) ?: break
                    if (reply.target == task.target.ipv4 && reply.identifier == socket.identifier && reply.sequence == index && reply.nonce == task.id) {
                        val rtt = (clock.nowMillis() - start).coerceAtLeast(0)
                        update(task) { it.copy(rttMillis = it.rttMillis + rtt) }
                        break
                    }
                }
            }
            update(task) { it.copy(status = ProbeStatus.Complete) }
        } catch (_: CancellationException) {
            update(task) { it.copy(status = ProbeStatus.Cancelled) }
        } catch (unavailable: ProbeUnavailableException) {
            update(task) { it.copy(status = ProbeStatus.Unavailable, unavailable = unavailable.reason) }
        } catch (_: Exception) {
            update(task) { it.copy(status = ProbeStatus.Unavailable, unavailable = failure) }
        } finally {
            synchronized(lock) {
                task.socket?.also { task.socket = null; runCatching { it.close() } }
                if (active === task) active = null
            }
        }
    }

    private fun update(task: Active, transform: (ProbeResult) -> ProbeResult) = synchronized(lock) {
        if (active === task) mutableResult.value?.takeIf { it.taskId == task.id }?.let { mutableResult.value = transform(it) }
    }

    companion object {
        internal fun eligible(session: SessionState, target: ProbeTarget): Boolean {
            return SessionObservation.from(session, session.applied?.saved?.profileId).accepts(target)
        }
    }
}

internal fun Ipv4Cidr.contains(ip: String): Boolean {
    fun bits(value: String) = value.split('.').fold(0L) { acc, part -> (acc shl 8) or part.toLong() }
    if (!com.github.zzwtsy.chuanmen.utils.isIpv4Literal(ip)) return false
    val mask = if (prefixLength == 0) 0 else (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
    return bits(address) and mask == bits(ip) and mask
}
