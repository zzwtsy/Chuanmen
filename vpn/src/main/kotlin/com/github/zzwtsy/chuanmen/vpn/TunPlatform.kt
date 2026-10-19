package com.github.zzwtsy.chuanmen.vpn

import com.github.zzwtsy.chuanmen.contracts.*

/** Kotlin 资源句柄由协调器持有，close 必须发生在对应 native 借用结束确认之后。 */
interface OwnedTun {
    val fd: Int
    fun close()
}

/** 平台生命周期操作由 Service 实现；核心替换不会替换这条正式资源关闭接线。 */
internal interface TunPlatform {
    val authorized: Boolean
    val networkAvailable: Boolean
    fun foreground(): Boolean
    fun leaveForeground()
    fun allowProtection(sessionId: SessionId)
    fun denyProtection()
    val protector: SocketProtector
    fun establish(spec: VpnInterfaceSpec): OwnedTun?
}

/** 路由只从核心实际 IPv4 与路由快照生成；DNS 用受控策略并明确进入 VPN。 */
internal fun interfaceSpec(snapshot: CoreSnapshot, saved: SavedRevision): VpnInterfaceSpec {
    val address = requireNotNull(snapshot.ipv4)
    val routes = if (saved.vpnPolicy.includeInternet) listOf(Ipv4Cidr("0.0.0.0", 0))
        else buildList {
            add(address.network())
            snapshot.routes.forEach { route ->
                route.ipv4?.let { add(it.network()) }
                addAll(route.proxyCidrs.map { it.network() })
            }
            saved.vpnPolicy.dnsServers.forEach { add(Ipv4Cidr(it, 32)) }
        }.distinct()
    return VpnInterfaceSpec(listOf(address), routes, saved.vpnPolicy.dnsServers, saved.core.mtu, saved.vpnPolicy.ipv6)
}

private fun Ipv4Cidr.network(): Ipv4Cidr {
    val bits = address.split('.').fold(0L) { value, part -> (value shl 8) or part.toLong() }
    val mask = if (prefixLength == 0) 0 else (0xffffffffL shl (32 - prefixLength)) and 0xffffffffL
    val network = bits and mask
    return Ipv4Cidr((3 downTo 0).joinToString(".") { ((network shr (it * 8)) and 255).toString() }, prefixLength)
}
