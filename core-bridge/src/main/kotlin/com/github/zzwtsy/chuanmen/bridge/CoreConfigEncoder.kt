package com.github.zzwtsy.chuanmen.bridge

import com.github.zzwtsy.chuanmen.contracts.Ipv4Assignment
import com.github.zzwtsy.chuanmen.contracts.SavedRevision
import com.google.gson.JsonPrimitive
import java.util.UUID

/** 受控 P02 输入映射到固定核心 schema；本地名称不参与实例身份或 TOML。 */
internal object CoreConfigEncoder {
    private fun quote(value: String) = JsonPrimitive(value).toString()

    fun encode(saved: SavedRevision): String {
        val core = saved.core
        val secret = requireNotNull(core.networkSecret) { "受控输入不完整" }
        require(core.peers.isNotEmpty()) { "受控输入不完整" }
        val id = UUID.nameUUIDFromBytes(core.instanceId.value.toByteArray(Charsets.UTF_8))
        return buildString {
            appendLine("instance_id = ${quote(id.toString())}")
            appendLine("instance_name = ${quote(core.instanceId.value)}")
            core.hostname?.let { appendLine("hostname = ${quote(it)}") }
            appendLine("listeners = []")
            when (val assignment = core.ipv4) {
                Ipv4Assignment.Automatic -> appendLine("dhcp = true")
                is Ipv4Assignment.Static -> {
                    appendLine("dhcp = false")
                    // 固定核心对不带前缀的静态 IPv4 使用 /24；安装参数仍读取核心实际结果。
                    appendLine("ipv4 = ${quote(assignment.address)}")
                }
            }
            appendLine("[network_identity]")
            appendLine("network_name = ${quote(core.networkName)}")
            appendLine("network_secret = ${quote(secret.revealForCore())}")
            core.peers.forEach {
                appendLine("[[peer]]")
                appendLine("uri = ${quote(it.value)}")
            }
            appendLine("[flags]")
            appendLine("mtu = ${core.mtu}")
            appendLine("enable_ipv6 = false")
            appendLine("no_tun = false")
        }
    }
}
