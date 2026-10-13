package com.github.zzwtsy.chuanmen.feature.devices

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.github.zzwtsy.chuanmen.contracts.AppliedSessionSnapshot
import com.github.zzwtsy.chuanmen.shared.designsystem.ConfigurationGroup
import com.github.zzwtsy.chuanmen.shared.designsystem.CopyIpButton
import com.github.zzwtsy.chuanmen.shared.designsystem.DeviceStatistic

@Composable
fun DevicesScreen(applied: AppliedSessionSnapshot?) {
    Text("设备", style = MaterialTheme.typography.headlineSmall)
    if (applied == null) {
        Text("当前查看网络未连接")
        return
    }
    ConfigurationGroup("本机 · 模拟数据") {
        Text(applied.saved.core.hostname ?: "名称未报告")
        applied.virtualIpv4.value?.let { Text(it); CopyIpButton(it) }
        DeviceStatistic("NAT", "未知 / 尚未探测")
        DeviceStatistic("核心版本", "未报告")
        DeviceStatistic("路径", "未知路径")
        DeviceStatistic("延迟", "未测得")
    }
    Text("其他设备暂无采样")
}
