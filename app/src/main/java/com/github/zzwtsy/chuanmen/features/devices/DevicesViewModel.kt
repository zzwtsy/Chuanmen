package com.github.zzwtsy.chuanmen.features.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.vpn.DataFreshness
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import com.github.zzwtsy.chuanmen.lib.vpn.CoreMetric
import com.github.zzwtsy.chuanmen.lib.vpn.CorePeer
import com.github.zzwtsy.chuanmen.lib.vpn.MetricUnavailable
import com.github.zzwtsy.chuanmen.lib.vpn.SessionObservation
import com.github.zzwtsy.chuanmen.lib.vpn.ConnectionProbe
import com.github.zzwtsy.chuanmen.lib.vpn.ProbeTarget
import com.github.zzwtsy.chuanmen.lib.vpn.ProbeStart
import com.github.zzwtsy.chuanmen.lib.vpn.ProbeResult
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/** 匹配查看网络、实际会话代次和观测；页面不拥有会话或采样任务。 */
class DevicesViewModel(sessions: StateFlow<SessionState>, viewedProfileId: StateFlow<LocalProfileId?>, private val probes: ConnectionProbe? = null) : ViewModel() {
    private fun scope(session: SessionState, viewed: LocalProfileId?) = DeviceScope(session.sessionId, viewed)
    private var model = DevicesModel(scope(sessions.value, viewedProfileId.value), project(sessions.value, viewedProfileId.value))
    private val mutableState = MutableStateFlow(model.render())
    val state: StateFlow<DevicesUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            mutableState.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { visible ->
                if (visible) combine(sessions, viewedProfileId, probes?.result ?: MutableStateFlow<ProbeResult?>(null)) { session, viewed, probe ->
                    DeviceEvent.Observed(scope(session, viewed), project(session, viewed), probe)
                }.collect(::dispatch)
            }
        }
    }

    fun act(action: DeviceAction) {
        when (action) {
            DeviceAction.StartProbe -> {
                val target = (model.detail as? DeviceDetailState.Current)?.card?.probeTarget ?: return
                when (probes?.start(target)) {
                    ProbeStart.Started -> probes.result.value?.let { dispatch(DeviceEvent.ProbeStarted(it)) }
                    ProbeStart.Busy -> dispatch(DeviceEvent.Notice("已有探测正在进行，请先取消"))
                    ProbeStart.Unavailable -> {
                        probes.result.value?.takeIf { it.target == target }?.let { dispatch(DeviceEvent.ProbeStarted(it)) }
                        dispatch(DeviceEvent.Notice("当前无法探测"))
                    }
                    null -> dispatch(DeviceEvent.Notice("当前无法探测"))
                }
            }
            DeviceAction.CancelProbe -> cancelOwned(ownedProbe())
            else -> dispatch(DeviceEvent.Action(action))
        }
    }

    private fun dispatch(event: DeviceEvent) {
        val before = ownedProbe()
        model = DevicesReducer.reduce(model, event)
        mutableState.value = model.render()
        if (before != null && (ownedProbe() != before || model.detail is DeviceDetailState.Stale)) cancelOwned(before)
    }

    private fun ownedProbe() = (model.detail as? DeviceDetailState.Open)?.binding
    private fun cancelOwned(binding: ProbeBinding?) { binding?.let { probes?.cancel(it.target, it.taskId) } }

    override fun onCleared() { cancelOwned(ownedProbe()) }

    companion object {
        internal fun project(session: SessionState, viewedId: LocalProfileId?): DevicesUiState {
            val observation = SessionObservation.from(session, viewedId)
            val applied = observation.applied ?: return DevicesUiState()
            if (!observation.confirmed) {
                return DevicesUiState(notice = "当前查看网络运行结果未确认")
            }
            val actual = observation.core
            val index = observation.index
            val local = LocalDeviceUiState(
                actual?.localHostname?.value ?: applied.saved.core.hostname ?: "名称未报告", applied.virtualIpv4.value,
                actual?.let { natType(it.udpNatType) }, actual?.let { natType(it.tcpNatType) },
                actual?.localVersion?.value ?: session.buildInfo?.takeIf { it.freshness == DataFreshness.Fresh }?.value?.coreVersion ?: "未报告",
                historicalAddress = !observation.addressIsCurrent,
            )
            val peers = index?.members.orEmpty().sorted().map { id ->
                val peer = index?.peers?.get(id)
                val route = index?.routes?.get(id)
                val hop = route?.nextHopPeerId?.value
                val path = if (hop == null) "未知路径" else if (hop == id) "直连" else "中继（下一跳 $hop）"
                DeviceCardUiState(route?.hostname?.value ?: "设备 $id", route?.ipv4?.address, peer?.connections?.toString() ?: "无邻居连接",
                    route?.version?.takeIf(String::isNotBlank) ?: "未报告", route?.cost?.toString() ?: "未报告",
                    route?.proxyCidrs.orEmpty().map { "路由 ${it.address}/${it.prefixLength}" },
                    identity = DeviceIdentity(applied.sessionId, id), path = path,
                    udpNat = route?.udpNatType?.natText() ?: "未采样",
                    tcpNat = route?.tcpNatType?.natText() ?: "未采样",
                    statistics = peer.linkStatistics(),
                    probeTarget = observation.probeTarget(id),
                    observedTarget = route?.ipv4?.address?.let { ip -> actual?.attachmentId?.let { ProbeTarget(applied.sessionId, it, id, ip) } },
                )
            }
            val relays = index?.relays.orEmpty().sorted().map { id ->
                val peer = requireNotNull(index?.peers?.get(id))
                DeviceCardUiState("中继 $id", null, peer.connections.toString(), "未报告", "不适用", emptyList(),
                    identity = DeviceIdentity(applied.sessionId, id), path = "公共中继连接（跨网络）",
                    statistics = peer.linkStatistics())
            }
            val notice = if (actual == null) "其他设备暂无采样" else listOfNotNull(
                "等待其他设备加入".takeIf { peers.isEmpty() },
                "存在身份未确认的连接，仅统计已确认同网成员".takeIf { index?.unknown?.isNotEmpty() == true },
            ).joinToString("；").ifEmpty { null }
            return DevicesUiState(null, local, peers, notice, relays = relays)
        }
    }
}

private fun CoreMetric<*>.text() = value?.toString() ?: when (unavailable) {
    MetricUnavailable.Unsupported -> "不支持"
    MetricUnavailable.Missing -> "未采样"
    MetricUnavailable.OutOfRange -> "超出表示范围"
    else -> "指标无效"
}
private fun CoreMetric<Int>.natText() = value?.let(::natType) ?: text()

/** 固定核心 common.proto 的枚举；未知编号原样保留，不猜测测量结果。 */
private fun natType(value: Int?): String = when (value) {
    null, 0 -> "未知 / 尚未探测"
    1 -> "OpenInternet"
    2 -> "NoPAT"
    3 -> "FullCone"
    4 -> "Restricted"
    5 -> "PortRestricted"
    6 -> "Symmetric"
    7 -> "SymUdpFirewall"
    8 -> "SymmetricEasyInc"
    9 -> "SymmetricEasyDec"
    else -> "未知类型 ($value)"
}

/** 只展示实际邻居连接的统计，成员路径经过中继时也不借用下一跳流量。 */
private fun CorePeer?.linkStatistics(): List<String> = if (this?.links.isNullOrEmpty()) {
    listOf("端到端收发：不支持；未报告邻居链路统计")
} else links.flatMap { link -> listOf("邻居连接 ${link.connectionId}",
    "上传 ${link.txBytes.text()} B / ${link.txPackets.text()} 包",
    "下载 ${link.rxBytes.text()} B / ${link.rxPackets.text()} 包", "链路 RTT ${link.latencyMicros.text()} µs") }
