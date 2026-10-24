package com.github.zzwtsy.chuanmen.lib.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.os.ParcelFileDescriptor
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 只绑定包含当前虚拟地址和目标路由的 VPN；不 protect，不回退物理网或外部 ping。 */
class AndroidIcmpTransportFactory(context: Context, private val sessions: kotlinx.coroutines.flow.StateFlow<SessionState>) : ProbeTransportFactory {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    override fun open(target: ProbeTarget): ProbeTransport {
        val session = sessions.value
        if (!ConnectionProbe.eligible(session, target)) throw ProbeUnavailableException(ProbeUnavailable.NoFreshSession)
        val address = InetAddress.getByName(target.ipv4)
        val localAddress = session.applied?.virtualIpv4?.value ?: error("address_unavailable")
        // 当前正式 Builder 没有应用排除名单，所有应用均进入 VPN。P05 引入名单时必须同步此判据。
        val vpn = connectivity.allNetworks.firstOrNull { network ->
            connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
                connectivity.getLinkProperties(network)?.let { properties ->
                    properties.linkAddresses.any { it.address.hostAddress == localAddress } && properties.routes.any { it.matches(address) }
                } == true
        } ?: throw ProbeUnavailableException(ProbeUnavailable.RouteUnavailable)
        val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_ICMP)
        try {
            vpn.bindSocket(fd)
            Os.bind(fd, InetAddress.getByName("0.0.0.0"), 0)
            Os.connect(fd, address, 0)
            // Android 的 fcntlInt 公共 API 晚于 minSdk；使用固定 NDK 的同步桥设置共享描述符标志。
            ParcelFileDescriptor.dup(fd).use { duplicate ->
                val request = BridgeProtocolCodec.request("probe_nonblocking").apply { addProperty("fd", duplicate.fd) }
                val response = BridgeProtocolCodec.decode(JniEnginePort.request(request.toString(), null))
                check(response["status"].asString == "confirmed" && response.getAsJsonObject("result")["nonblocking"].asBoolean)
            }
            val identifier = (Os.getsockname(fd) as InetSocketAddress).port
            return AndroidIcmpTransport(fd, target.ipv4, identifier)
        } catch (failure: Exception) {
            Os.close(fd)
            throw failure
        }
    }
}

private class AndroidIcmpTransport(
    private val fd: FileDescriptor, private val target: String, override val identifier: Int,
) : ProbeTransport {
    private val closed = AtomicBoolean(false)

    override suspend fun send(sequence: Int, nonce: Long) {
        currentCoroutineContext().ensureActive()
        val packet = IcmpEcho.packet(identifier, sequence, nonce)
        check(Os.sendto(fd, packet, 0, packet.size, 0, InetAddress.getByName(target), 0) == packet.size)
    }

    override suspend fun receive(timeoutMillis: Long): EchoReply? {
        val deadline = android.os.SystemClock.elapsedRealtime() + timeoutMillis
        val poll = StructPollfd().apply { fd = this@AndroidIcmpTransport.fd; events = OsConstants.POLLIN.toShort() }
        while (!closed.get()) {
            currentCoroutineContext().ensureActive()
            val remaining = deadline - android.os.SystemClock.elapsedRealtime()
            if (remaining <= 0) return null
            if (Os.poll(arrayOf(poll), minOf(remaining, 50).toInt()) == 0) continue
            val packet = ByteArray(2048)
            val source = InetSocketAddress(0)
            val size = try { Os.recvfrom(fd, packet, 0, packet.size, 0, source) }
            catch (failure: ErrnoException) { if (failure.errno == OsConstants.EAGAIN) continue else throw failure }
            if (source.address?.hostAddress != target) continue
            IcmpEcho.reply(packet.copyOf(size), target)?.let { return it }
        }
        return null
    }

    override fun close() { if (closed.compareAndSet(false, true)) Os.close(fd) }
}

/** ICMP datagram 不包含 IPv4 头；nonce 防止端口和序号复用时接受旧任务响应。 */
internal object IcmpEcho {
    fun packet(identifier: Int, sequence: Int, nonce: Long): ByteArray {
        val bytes = ByteBuffer.allocate(16).put(8).put(0).putShort(0).putShort(identifier.toShort())
            .putShort(sequence.toShort()).putLong(nonce).array()
        val checksum = checksum(bytes)
        bytes[2] = (checksum ushr 8).toByte(); bytes[3] = checksum.toByte()
        return bytes
    }
    fun reply(bytes: ByteArray, target: String): EchoReply? {
        if (bytes.size != 16 || bytes[0] != 0.toByte() || bytes[1] != 0.toByte() || checksum(bytes) != 0) return null
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(4)
        return EchoReply(target, buffer.short.toInt() and 0xffff, buffer.short.toInt() and 0xffff, buffer.long)
    }
    internal fun checksum(bytes: ByteArray): Int {
        var sum = 0
        for (index in bytes.indices step 2) sum += ((bytes[index].toInt() and 255) shl 8) or (bytes.getOrNull(index + 1)?.toInt()?.and(255) ?: 0)
        while (sum ushr 16 != 0) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv() and 0xffff
    }
}
