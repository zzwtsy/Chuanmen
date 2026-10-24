package com.github.zzwtsy.chuanmen.features.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.zzwtsy.chuanmen.lib.logging.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 页面只投影有界缓冲；筛选与阅读状态均不改变采集级别或写盘任务。 */
class LogsViewModel(private val logs: LogCollector) : ViewModel() {
    private var buffer = logs.state.value
    private var filter = LogFilter()
    private var reading: LogReadingState = LogReadingState.Following
    private val mutableState = MutableStateFlow(LogsUiState())
    val state: StateFlow<LogsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            // 页面隐藏时保留阅读身份和像素位置，但不持续过滤后台日志。
            mutableState.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { visible ->
                if (visible) logs.state.collect { latest ->
                    buffer = latest
                    val position = reading as? LogReadingState.Paused
                    if (position?.anchorId != null && buffer.records.none { it.id == position.anchorId }) {
                        reading = LogReadingState.Evicted(buffer.records.firstOrNull(filter::matches)?.id, position.baselineTailId)
                    }
                    publish()
                }
            }
        }
    }

    fun act(action: LogAction) {
        when (action) {
            is LogAction.Filter -> {
                val baseline = baseline()
                filter = action.filter
                reading = LogReadingState.Reading(null, baseline)
            }
            is LogAction.PauseAt -> reading = LogReadingState.Reading(action.recordId, baseline())
            LogAction.Follow -> reading = LogReadingState.Following
            LogAction.RetryPersistence -> logs.retryPersistence()
            is LogAction.DebugCollection -> logs.setDebugCollection(action.enabled)
        }
        publish()
    }

    private fun baseline(): String? = when (val position = reading) {
        is LogReadingState.Paused -> position.baselineTailId
        LogReadingState.Following -> buffer.records.lastOrNull()?.id
    }

    private fun publish() {
        val position = reading as? LogReadingState.Paused
        val boundary = buffer.records.indexOfFirst { it.id == position?.baselineTailId }
        val records = buffer.records.filter(filter::matches)
        mutableState.value = LogsUiState(records, filter, buffer.dropped, buffer.persistenceError, buffer.debugEnabled, buffer.debugError,
            reading = reading, newRecords = if (position == null) 0 else buffer.records.drop(boundary + 1).count(filter::matches),
            sessions = buffer.records.mapNotNull { record -> record.sessionId?.let { it to requireNotNull(record.generation) } }.distinct())
    }
}
