package com.github.zzwtsy.chuanmen.features.logs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.zzwtsy.chuanmen.lib.logging.*
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun LogsRoute(model: LogsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    LogsScreen(state, model::act)
}

/** 稳定 key 让上滑阅读时的新记录保持锚点；点击提示后才恢复跟随。 */
@Composable
fun LogsScreen(state: LogsUiState, onAction: (LogAction) -> Unit = {}) {
    val list = rememberLazyListState()
    val action by rememberUpdatedState(onAction)
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to list.firstVisibleItemIndex }
            .distinctUntilChanged().collect { (scrolling, _) ->
                if (scrolling && list.canScrollForward) list.layoutInfo.visibleItemsInfo.firstOrNull { it.key != "filters" }?.let { item ->
                    action(LogAction.PauseAt(item.key as String))
                }
            }
    }
    LaunchedEffect(state.records.lastOrNull()?.id, state.following) {
        if (state.following && state.records.isNotEmpty() && !list.isScrollInProgress) list.scrollToItem(state.records.size)
    }
    LaunchedEffect(state.anchorId, state.anchorEvicted) {
        if (state.anchorEvicted) list.scrollToItem(state.records.indexOfFirst { it.id == state.anchorId }.let { if (it < 0) 0 else it + 1 })
    }
    Column(Modifier.fillMaxSize()) {
        if (state.persistenceError) {
            Text("日志写入失败，正在仅保留内存记录", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { onAction(LogAction.RetryPersistence) }) { Text("重试日志写入") }
        }
        if (state.anchorEvicted) Text("旧记录已清理，已定位现存最早记录")
        if (!state.following) TextButton(onClick = { onAction(LogAction.Follow) }) { Text("${state.newRecords} 条新记录 · 回到底部") }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list) {
            item(key = "filters") {
                Column {
                    Text("日志", style = MaterialTheme.typography.headlineSmall)
                    Text("已丢弃 ${state.dropped} 条")
                    OutlinedTextField(state.filter.keyword, { onAction(LogAction.Filter(state.filter.copy(keyword = it))) }, label = { Text("搜索安全日志消息") }, modifier = Modifier.fillMaxWidth())
                    FlowRow {
                        FilterChip(state.filter.source == null, { onAction(LogAction.Filter(state.filter.copy(source = null))) }, label = { Text("全部来源") })
                        LogSource.entries.forEach { source -> FilterChip(state.filter.source == source,
                            { onAction(LogAction.Filter(state.filter.copy(source = source))) }, label = { Text(source.name) }) }
                    }
                    Text("最低显示级别")
                    FlowRow {
                        LogLevel.entries.forEach { level -> FilterChip(state.filter.minimumLevel == level,
                            { onAction(LogAction.Filter(state.filter.copy(minimumLevel = level))) }, label = { Text(level.name) }) }
                    }
                    FlowRow {
                        FilterChip(state.filter.sessionId == null, { onAction(LogAction.Filter(state.filter.copy(sessionId = null))) }, label = { Text("全部会话 / 进程") })
                        state.sessions.forEach { (id, generation) -> FilterChip(state.filter.sessionId == id,
                            { onAction(LogAction.Filter(state.filter.copy(sessionId = id))) }, label = { Text("会话 $generation") }) }
                    }
                    Row {
                        Checkbox(state.debugEnabled, { onAction(LogAction.DebugCollection(it)) })
                        Text("采集 Debug 日志")
                    }
                    if (state.debugError) Text("无法更改采集级别，请确认服务可用后重试", color = MaterialTheme.colorScheme.error)
                    if (state.records.isEmpty()) Text("暂无匹配日志")
                }
            }
            items(state.records, key = { it.id }) { record ->
                Column {
                    val time = remember(record.wallTimeMillis) {
                        java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date(record.wallTimeMillis))
                    }
                    Text("$time · ${record.source} · ${record.level} · ${record.module}", style = MaterialTheme.typography.labelSmall)
                    record.generation?.let { Text("会话 $it", style = MaterialTheme.typography.labelSmall) }
                    Text(record.message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
