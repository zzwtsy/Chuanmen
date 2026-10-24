package com.github.zzwtsy.chuanmen.features.devices.components

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.github.zzwtsy.chuanmen.components.CopyIpButton
import com.github.zzwtsy.chuanmen.components.DeviceStatistic
import com.github.zzwtsy.chuanmen.components.SectionCard
import com.github.zzwtsy.chuanmen.features.devices.DeviceCardUiState
import com.github.zzwtsy.chuanmen.features.devices.DeviceAction

/** 只渲染设备页已经核对过的展示行，不查询真实核心或关联路由。 */
@Composable
internal fun DeviceCard(state: DeviceCardUiState, expanded: Boolean, onAction: (DeviceAction) -> Unit) {
    SectionCard(state.title) {
        state.ip?.let { Text(it) }
        CopyIpButton(state.ip.orEmpty(), enabled = state.online && state.ip != null)
        Text(state.path)
        state.identity?.let { identity ->
            TextButton(onClick = { onAction(DeviceAction.Expand(identity)) }) { Text(if (expanded) "收起连接摘要" else "展开连接摘要") }
            TextButton(onClick = { onAction(DeviceAction.Details(identity)) }) { Text("设备详情") }
        }
        if (expanded) {
            DeviceStatistic("邻居连接", state.connections)
            DeviceStatistic("核心版本", state.version)
            DeviceStatistic("路由代价", state.routeCost)
            state.routes.forEach { Text(it) }
        }
    }
}
