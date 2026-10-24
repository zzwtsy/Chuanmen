package com.github.zzwtsy.chuanmen.features.devices

import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.vpn.ProbeResult
import com.github.zzwtsy.chuanmen.lib.vpn.ProbeTarget
import com.github.zzwtsy.chuanmen.lib.vpn.SessionId

internal data class DeviceScope(val sessionId: SessionId?, val viewedId: LocalProfileId?)
internal data class ProbeBinding(val taskId: Long, val target: ProbeTarget)

/** 旧详情保留快照和自身探测结果；关闭后不接收进程中其他任务留下的结果。 */
internal sealed interface DeviceDetailState {
    data object Closed : DeviceDetailState
    sealed interface Open : DeviceDetailState {
        val card: DeviceCardUiState
        val binding: ProbeBinding?
        val result: ProbeResult?
    }
    data class Current(override val card: DeviceCardUiState, override val binding: ProbeBinding? = null,
        override val result: ProbeResult? = null) : Open
    data class Stale(override val card: DeviceCardUiState, override val binding: ProbeBinding?,
        override val result: ProbeResult?) : Open
}

internal data class DevicesModel(
    val scope: DeviceScope,
    val page: DevicesUiState,
    val detail: DeviceDetailState = DeviceDetailState.Closed,
    val expanded: Set<DeviceIdentity> = emptySet(),
    val notice: String? = null,
) {
    fun render(): DevicesUiState {
        val open = detail as? DeviceDetailState.Open
        return page.copy(detail = open?.card, expanded = expanded, probe = open?.result, probeNotice = notice,
            probeHistorical = detail is DeviceDetailState.Stale || (open != null && open.card.probeTarget == null))
    }
}

internal sealed interface DeviceEvent {
    data class Observed(val scope: DeviceScope, val page: DevicesUiState, val probe: ProbeResult?) : DeviceEvent
    data class Action(val action: DeviceAction) : DeviceEvent
    data class ProbeStarted(val result: ProbeResult) : DeviceEvent
    data class Notice(val message: String?) : DeviceEvent
}

/** 所有状态迁移均为纯函数；取消 socket 和启动任务由 ViewModel 根据归属变化执行。 */
internal object DevicesReducer {
    fun reduce(model: DevicesModel, event: DeviceEvent): DevicesModel = when (event) {
        is DeviceEvent.Observed -> if (model.scope != event.scope) DevicesModel(event.scope, event.page) else {
            val open = model.detail as? DeviceDetailState.Open
            val detail = if (open == null) DeviceDetailState.Closed else {
                val current = (event.page.peers + event.page.relays).firstOrNull { it.identity == open.card.identity }
                val binding = open.binding?.takeIf { current == null || it.target == current.observedTarget }
                val result = (event.probe?.takeIf { it.matches(binding) } ?: open.result?.takeIf { it.matches(binding) })
                if (current != null) DeviceDetailState.Current(current, binding, result)
                else DeviceDetailState.Stale(open.card.copy(online = false, unavailable = "设备离线或数据已过期", probeTarget = null), binding, result)
            }
            model.copy(page = event.page, detail = detail,
                expanded = model.expanded.intersect((event.page.peers + event.page.relays).mapNotNull { it.identity }.toSet()))
        }
        is DeviceEvent.ProbeStarted -> {
            val current = model.detail as? DeviceDetailState.Current
            if (current?.card?.probeTarget != event.result.target) model else model.copy(
                detail = current.copy(binding = ProbeBinding(event.result.taskId, event.result.target), result = event.result), notice = null)
        }
        is DeviceEvent.Notice -> model.copy(notice = event.message)
        is DeviceEvent.Action -> when (val action = event.action) {
            is DeviceAction.Details -> (model.page.peers + model.page.relays).firstOrNull { it.identity == action.identity }?.let {
                model.copy(detail = DeviceDetailState.Current(it), notice = null)
            } ?: model
            is DeviceAction.Expand -> model.copy(expanded = if (action.identity in model.expanded)
                model.expanded - action.identity else model.expanded + action.identity)
            DeviceAction.CloseDetails -> model.copy(detail = DeviceDetailState.Closed, notice = null)
            else -> model
        }
    }

    private fun ProbeResult.matches(binding: ProbeBinding?) = binding != null && taskId == binding.taskId && target == binding.target
}
