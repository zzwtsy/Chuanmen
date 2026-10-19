package com.github.zzwtsy.chuanmen.features.devices

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.zzwtsy.chuanmen.components.CopyIpButton
import com.github.zzwtsy.chuanmen.components.DeviceStatistic
import com.github.zzwtsy.chuanmen.components.SectionCard
import com.github.zzwtsy.chuanmen.features.devices.components.DeviceCard

@Composable
internal fun DevicesRoute(model: DevicesViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    DevicesScreen(state)
}

@Composable
fun DevicesScreen(state: DevicesUiState) {
    Text("设备", style = MaterialTheme.typography.headlineSmall)
    state.notice?.let { Text(it) }
    state.local?.let { local ->
        SectionCard("本机") {
            Text(local.name)
            local.ip?.let { Text(it); CopyIpButton(it) }
            if (local.udpNat == null) DeviceStatistic("NAT", "未知 / 尚未探测") else {
                DeviceStatistic("UDP NAT", local.udpNat)
                DeviceStatistic("TCP NAT", local.tcpNat ?: "未知 / 尚未探测")
            }
            DeviceStatistic("核心版本", local.version)
            DeviceStatistic("路径", "未知路径")
            DeviceStatistic("延迟", "未测得")
        }
    }
    state.peers.forEach { DeviceCard(it) }
    state.peersNotice?.let { Text(it) }
}
