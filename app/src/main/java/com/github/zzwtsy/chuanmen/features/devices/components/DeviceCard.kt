package com.github.zzwtsy.chuanmen.features.devices.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.github.zzwtsy.chuanmen.components.CopyIpButton
import com.github.zzwtsy.chuanmen.components.DeviceStatistic
import com.github.zzwtsy.chuanmen.components.SectionCard
import com.github.zzwtsy.chuanmen.features.devices.DeviceCardUiState

/** 只渲染设备页已经核对过的展示行，不查询真实核心或关联路由。 */
@Composable
internal fun DeviceCard(state: DeviceCardUiState) {
    SectionCard(state.title) {
        state.ip?.let { Text(it); CopyIpButton(it) }
        DeviceStatistic("连接", state.connections)
        DeviceStatistic("核心版本", state.version)
        DeviceStatistic("路由代价", state.routeCost)
        state.routes.forEach { Text(it) }
    }
}
