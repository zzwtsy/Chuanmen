package com.github.zzwtsy.chuanmen.lib

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 外观偏好仅保存在内存，不提供持久化保证。 */
data class Appearance(val darkTheme: Boolean? = null, val dynamicColor: Boolean = true)

/** 根主题与设置共享的 Activity 生命周期内存偏好，不持久化。 */
class AppearancePreferences {
    private val mutableAppearance = MutableStateFlow(Appearance())
    val state = mutableAppearance.asStateFlow()
    fun update(appearance: Appearance) { mutableAppearance.value = appearance }
}
