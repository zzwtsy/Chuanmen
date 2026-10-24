package com.github.zzwtsy.chuanmen.features.settings

import com.github.zzwtsy.chuanmen.lib.Appearance

/** 互联网范围只读；外观来自进程共用的持久偏好。 */
data class SettingsUiState(val appearance: Appearance = Appearance(), val internetScope: String = "暂无网络策略", val dnsScope: String = "", val error: String? = null)
