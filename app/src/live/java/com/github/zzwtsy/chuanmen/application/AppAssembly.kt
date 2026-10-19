package com.github.zzwtsy.chuanmen.application

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/** live 只装配应用仓库和服务客户端；ViewModel 作用域不拥有真实会话。 */
object AppAssembly {
    private lateinit var application: LiveApplication
    private var notificationRequested = false
    fun attach(activity: ComponentActivity) {
        application = activity.application as LiveApplication
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
        val repository by application.repository.collectAsState()
        if (repository.profiles.value.isEmpty()) ControlledEntry { application.setRepository(it) }
        else ChuanmenApp()
    }

    val factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == ChuanmenViewModel::class.java)
            val model = ChuanmenViewModel { AppDependencies(application.profiles, application.sessions, AppReadings()) }
            @Suppress("UNCHECKED_CAST")
            return model as T
        }
    }
}
