package com.github.zzwtsy.chuanmen.feature.logs

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** 固定脱敏样本的展示入口，日志订阅与持久化在 P04 接入。 */
@Composable
fun LogsScreen(logs: List<String>) {
    Text("日志", style = MaterialTheme.typography.headlineSmall)
    if (logs.isEmpty()) Text("暂无日志")
    logs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
}
