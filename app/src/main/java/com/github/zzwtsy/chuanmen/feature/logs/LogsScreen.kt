package com.github.zzwtsy.chuanmen.feature.logs

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** 展示装配提供的有界脱敏读数；真实生命周期与核心进程日志分别标记，持久化归 P04。 */
@Composable
fun LogsScreen(logs: List<String>) {
    Text("日志", style = MaterialTheme.typography.headlineSmall)
    if (logs.isEmpty()) Text("暂无日志")
    logs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
}
