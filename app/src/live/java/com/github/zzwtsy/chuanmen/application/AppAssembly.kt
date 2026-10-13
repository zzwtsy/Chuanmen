package com.github.zzwtsy.chuanmen.application

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** 即使构建门槛被手工绕过，正式入口也明确失败，不能生成模拟会话。 */
object AppAssembly {
    val factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            error("LIVE_ENGINE_MISSING: 正式服务与桥接尚未接入，请等待 P02")
    }
}
