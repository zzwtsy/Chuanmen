package com.github.zzwtsy.chuanmen.features.logs

import com.github.zzwtsy.chuanmen.lib.logging.*

/** 阅读边界来自未筛选缓冲；像素位置由 Compose 的 LazyListState 唯一持有。 */
sealed interface LogReadingState {
    data object Following : LogReadingState
    sealed interface Paused : LogReadingState {
        val anchorId: String?
        val baselineTailId: String?
    }
    data class Reading(override val anchorId: String?, override val baselineTailId: String?) : Paused
    data class Evicted(override val anchorId: String?, override val baselineTailId: String?) : Paused
}

data class LogsUiState(
    val records: List<LogRecord> = emptyList(), val filter: LogFilter = LogFilter(),
    val dropped: Long = 0, val persistenceError: Boolean = false,
    val debugEnabled: Boolean = false, val debugError: Boolean = false,
    val reading: LogReadingState = LogReadingState.Following,
    val newRecords: Int = 0,
    val sessions: List<Pair<String, Long>> = emptyList(),
) {
    val following get() = reading is LogReadingState.Following
    val anchorId get() = (reading as? LogReadingState.Paused)?.anchorId
    val anchorEvicted get() = reading is LogReadingState.Evicted
    val lines get() = records.map { "${it.source} · ${it.level} · ${it.module} · ${it.message}" }
}

sealed interface LogAction {
    data class Filter(val filter: LogFilter) : LogAction
    data class PauseAt(val recordId: String) : LogAction
    data object Follow : LogAction
    data object RetryPersistence : LogAction
    data class DebugCollection(val enabled: Boolean) : LogAction
}
