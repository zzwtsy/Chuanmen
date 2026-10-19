package com.github.zzwtsy.chuanmen.features.logs

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun LogsRoute(model: LogsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    LogsScreen(state)
}

/** 展示有界脱敏记录，持久化归 P04。 */
@Composable
fun LogsScreen(state: LogsUiState) {
    Text("日志", style = MaterialTheme.typography.headlineSmall)
    if (state.lines.isEmpty()) Text("暂无日志")
    state.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
}
