package com.github.zzwtsy.chuanmen.lib.vpn

import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import java.util.Collections

/** 路由属于同网成员；邻居连接还可能来自公共中继，不能直接按连接集合统计设备。 */
class SnapshotPeerIndex private constructor(
    val peers: Map<Long, CorePeer>,
    val routes: Map<Long, CoreRoute>,
    val members: Set<Long>,
    val relays: Set<Long>,
    val unknown: Set<Long>,
) {
    companion object {
        internal fun from(snapshot: CoreSnapshot): SnapshotPeerIndex {
            val peers = snapshot.peers.groupBy { it.peerId }.mapValues { (_, values) ->
                val active = values.filter { it.connections > 0 }
                val role = when {
                    active.any { it.networkRole == PeerNetworkRole.Member } -> PeerNetworkRole.Member
                    active.isNotEmpty() && active.all { it.networkRole == PeerNetworkRole.Relay } -> PeerNetworkRole.Relay
                    else -> PeerNetworkRole.Unknown
                }
                CorePeer(values.first().peerId, values.sumOf { it.connections },
                    Collections.unmodifiableList(values.flatMap { it.links }.distinctBy { it.connectionId }), role)
            }
            val routes = snapshot.routes.associate { it.peerId to it.copy(proxyCidrs = Collections.unmodifiableList(it.proxyCidrs.toList())) }
            val local = setOfNotNull(snapshot.localPeerId)
            val members = (routes.keys + peers.values.filter {
                it.connections > 0 && it.networkRole == PeerNetworkRole.Member
            }.map { it.peerId }) - local
            val active = peers.values.filter { it.connections > 0 && it.peerId !in members && it.peerId !in local }
            return SnapshotPeerIndex(Collections.unmodifiableMap(peers), Collections.unmodifiableMap(routes), Collections.unmodifiableSet(members),
                Collections.unmodifiableSet(active.filter { it.networkRole == PeerNetworkRole.Relay }.map { it.peerId }.toSet()),
                Collections.unmodifiableSet(active.filter { it.networkRole == PeerNetworkRole.Unknown }.map { it.peerId }.toSet()))
        }
    }
}

/** 只适配 Service 的事实与新鲜度，不持有会话、不启动任务，也不自行延长观测有效期。 */
class SessionObservation private constructor(
    val applied: AppliedSessionSnapshot?,
    val confirmed: Boolean,
    val core: CoreSnapshot?,
    val addressIsCurrent: Boolean,
    private val probeSpec: VpnInterfaceSpec?,
) {
    val index: SnapshotPeerIndex? get() = core?.peerIndex

    fun probeTarget(peerId: Long): ProbeTarget? {
        val snapshot = core ?: return null
        val attachment = snapshot.attachmentId ?: return null
        if (peerId !in snapshot.peerIndex.members) return null
        val ip = snapshot.peerIndex.routes[peerId]?.ipv4?.address ?: return null
        if (probeSpec?.routes?.any { it.contains(ip) } != true) return null
        return ProbeTarget(snapshot.sessionId, attachment, peerId, ip)
    }

    fun accepts(target: ProbeTarget): Boolean = probeTarget(target.peerId) == target

    companion object {
        fun from(session: SessionState, viewedId: LocalProfileId?): SessionObservation {
            val applied = session.applied?.takeIf { it.saved.profileId == viewedId }
            val confirmed = applied != null && applied.sessionId == session.sessionId &&
                applied.freshness == DataFreshness.Fresh && session.phase != ConnectionPhase.OutcomeUnknown
            val core = session.currentCore(viewedId)
            val runtime = session.runtime?.takeIf { it.freshness == DataFreshness.Fresh }?.value?.takeIf {
                core != null && it.sessionId == core.sessionId && it.attachmentId == core.attachmentId
            }
            val spec = runtime?.interfaceSpec?.takeIf { it.freshness == DataFreshness.Fresh }?.value
            return SessionObservation(applied, confirmed, core, session.hasCurrentAddress(viewedId), spec)
        }
    }
}
