package com.github.zzwtsy.chuanmen.features.logs

/** 行来自真实生命周期和核心诊断，空列表表示没有记录。 */
data class LogsUiState(val lines: List<String> = emptyList())
