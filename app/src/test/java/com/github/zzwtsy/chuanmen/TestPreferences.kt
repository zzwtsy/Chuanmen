package com.github.zzwtsy.chuanmen

import com.github.zzwtsy.chuanmen.lib.AppearancePreferences
import com.github.zzwtsy.chuanmen.lib.PreferenceValues
import kotlinx.coroutines.Dispatchers

/** 状态测试的显式偏好夹具；正式 SharedPreferences 提交另作存储回归。 */
fun testPreferences(): AppearancePreferences {
    var stored = PreferenceValues()
    return AppearancePreferences({ stored }, { stored = it; true }, Dispatchers.Main.immediate)
}
