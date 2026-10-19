package com.github.zzwtsy.chuanmen

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.zzwtsy.chuanmen.features.configuration.ConfigurationEntry
import com.github.zzwtsy.chuanmen.features.configuration.ConfigurationViewModel
import com.github.zzwtsy.chuanmen.features.devices.DevicesViewModel
import com.github.zzwtsy.chuanmen.features.logs.LogsViewModel
import com.github.zzwtsy.chuanmen.features.overview.OverviewViewModel
import com.github.zzwtsy.chuanmen.features.settings.SettingsViewModel
import com.github.zzwtsy.chuanmen.lib.AppearancePreferences
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId

/** 装配应用仓库和服务客户端；ViewModel 作用域不拥有真实会话。 */
object AppAssembly {
    private lateinit var application: ChuanmenApplication
    private var notificationRequested = false
    fun attach(activity: ComponentActivity) {
        application = activity.application as ChuanmenApplication
        val notifications = activity.activityResultRegistry.register("chuanmen-notifications", activity, ActivityResultContracts.RequestPermission()) { }
        val vpn = activity.activityResultRegistry.register("chuanmen-vpn", activity, ActivityResultContracts.StartActivityForResult()) { result ->
            application.sessions.authorizationResult(result.resultCode == Activity.RESULT_OK)
            if (result.resultCode == Activity.RESULT_OK && Build.VERSION.SDK_INT >= 33 && !notificationRequested &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) { notificationRequested = true; notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        application.sessions.attachAuthorization(activity) { vpn.launch(it) }
    }
    fun detach(activity: ComponentActivity) { application.sessions.detachAuthorization(activity) }

    @Composable fun Content() {
        val repository by application.repository.collectAsStateWithLifecycle()
        val profiles by repository.profiles.collectAsStateWithLifecycle()
        if (profiles.isEmpty()) ConfigurationEntry { application.setRepository(it) }
        else ChuanmenApp()
    }

    val factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == ChuanmenViewModel::class.java)
            val model = ChuanmenViewModel(application.profiles.profiles, application.sessions.state)
            @Suppress("UNCHECKED_CAST")
            return model as T
        }
    }
    fun featureFactory(viewedProfileId: kotlinx.coroutines.flow.StateFlow<LocalProfileId?>, preferences: AppearancePreferences) = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val model = when (modelClass) {
                OverviewViewModel::class.java -> OverviewViewModel(application.profiles, application.sessions, viewedProfileId)
                DevicesViewModel::class.java -> DevicesViewModel(application.sessions.state, viewedProfileId)
                LogsViewModel::class.java -> LogsViewModel(application.sessions.state)
                SettingsViewModel::class.java -> SettingsViewModel(application.profiles, viewedProfileId, preferences)
                ConfigurationViewModel::class.java -> ConfigurationViewModel(application.profiles, application.sessions, viewedProfileId)
                else -> error("未知功能 ViewModel")
            }
            @Suppress("UNCHECKED_CAST")
            return model as T
        }
    }

}
