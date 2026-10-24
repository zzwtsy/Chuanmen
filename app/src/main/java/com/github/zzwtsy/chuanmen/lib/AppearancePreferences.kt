package com.github.zzwtsy.chuanmen.lib

import android.content.Context
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class Appearance(val darkTheme: Boolean? = null, val dynamicColor: Boolean = true)
internal data class PreferenceValues(val appearance: Appearance = Appearance(), val viewedId: LocalProfileId? = null)

/** 非敏感偏好后台串行提交；提交失败不发布新值，查看身份不作为连接目标。 */
class AppearancePreferences internal constructor(
    private val read: () -> PreferenceValues,
    private val commit: (PreferenceValues) -> Boolean,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutex = Mutex()
    private var loaded = false
    private val mutableAppearance = MutableStateFlow(Appearance())
    val state = mutableAppearance.asStateFlow()
    private val mutableViewed = MutableStateFlow<LocalProfileId?>(null)
    val lastViewed = mutableViewed.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    suspend fun load() = withContext(dispatcher) {
        mutex.withLock {
            if (loaded) return@withLock
            try {
                val values = read()
                mutableAppearance.value = values.appearance
                mutableViewed.value = values.viewedId
                loaded = true
            } catch (_: RuntimeException) { mutableError.value = "无法读取偏好，请重试" }
        }
    }
    suspend fun update(appearance: Appearance): Boolean = change { it.copy(appearance = appearance) }
    suspend fun view(id: LocalProfileId?): Boolean = change { it.copy(viewedId = id) }
    private suspend fun change(transform: (PreferenceValues) -> PreferenceValues): Boolean = withContext(dispatcher) {
        mutex.withLock {
            if (!loaded) {
                try {
                    val initial = read()
                    mutableAppearance.value = initial.appearance; mutableViewed.value = initial.viewedId; loaded = true
                } catch (_: RuntimeException) { mutableError.value = "无法读取偏好，未保存"; return@withLock false }
            }
            val next = transform(PreferenceValues(state.value, lastViewed.value))
            val success = try { commit(next) } catch (_: RuntimeException) { false }
            if (success) {
                mutableAppearance.value = next.appearance; mutableViewed.value = next.viewedId; mutableError.value = null
            } else mutableError.value = "无法保存偏好，已保留原值"
            success
        }
    }
    companion object {
        fun open(context: Context): AppearancePreferences {
            val prefs by lazy { context.applicationContext.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
            return AppearancePreferences(read = {
                PreferenceValues(Appearance(when (prefs.getString("theme", "system")) {
                    "dark" -> true; "light" -> false; else -> null
                }, prefs.getBoolean("dynamicColor", true)), prefs.getString("viewedId", null)?.let {
                    runCatching { LocalProfileId(it) }.getOrNull()
                })
            }, commit = { values ->
                prefs.edit().putString("theme", when (values.appearance.darkTheme) { true -> "dark"; false -> "light"; null -> "system" })
                    .putBoolean("dynamicColor", values.appearance.dynamicColor).putString("viewedId", values.viewedId?.value).commit()
            })
        }
    }
}
