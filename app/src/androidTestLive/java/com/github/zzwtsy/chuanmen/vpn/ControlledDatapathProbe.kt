package com.github.zzwtsy.chuanmen.vpn

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import org.json.JSONObject

/** 只发送带本轮身份的合成回显载荷；连接、回包截断与超时均使调用失败。 */
internal object ControlledDatapathProbe {
    fun roundtrip(protocol: String, payload: ByteArray): ByteArray {
        if (protocol == "tcp") return Socket().use { socket ->
            // UDP 外层断开由对端探活发现；业务首次连接使用 30 秒预算，仍须取得本轮真实回包。
            socket.connect(InetSocketAddress("10.222.0.1", 31271), 30_000)
            socket.soTimeout = 10_000
            socket.getOutputStream().write(payload)
            val response = ByteArray(payload.size)
            var offset = 0
            while (offset < response.size) {
                val count = socket.getInputStream().read(response, offset, response.size - offset)
                check(count > 0) { "TCP_RESPONSE_TRUNCATED" }
                offset += count
            }
            response
        }
        return DatagramSocket().use { socket ->
            socket.soTimeout = 10_000
            socket.connect(InetAddress.getByName("10.222.0.1"), 31272)
            socket.send(DatagramPacket(payload, payload.size))
            val response = DatagramPacket(ByteArray(4096), 4096)
            socket.receive(response)
            response.data.copyOf(response.length)
        }
    }

    fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 只在 VPN 确认停止后查询受控对端；实际连接和可达路由同时归零才允许下一轮。 */
    fun peerStatus(host: String, runId: String): JSONObject = Socket().use { socket ->
        socket.connect(InetSocketAddress(host, 31274), 2_000)
        socket.soTimeout = 2_000
        socket.getOutputStream().write("$runId\n".toByteArray())
        socket.shutdownOutput()
        val response = socket.getInputStream().bufferedReader().readLine()
        check(response != null && response.length < 8192) { "PEER_STATUS_MISSING" }
        JSONObject(response).also { check(it.getString("run_id") == runId) { "PEER_STATUS_WRONG_RUN" } }
    }
}
