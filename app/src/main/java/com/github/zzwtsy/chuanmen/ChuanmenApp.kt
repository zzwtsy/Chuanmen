package com.github.zzwtsy.chuanmen

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import com.github.zzwtsy.chuanmen.features.configuration.lib.CreationAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.zzwtsy.chuanmen.features.configuration.ConfigurationEntry
import com.github.zzwtsy.chuanmen.features.configuration.ConfigurationRoute
import com.github.zzwtsy.chuanmen.features.configuration.ConfigurationViewModel
import com.github.zzwtsy.chuanmen.features.devices.DevicesRoute
import com.github.zzwtsy.chuanmen.features.devices.DevicesViewModel
import com.github.zzwtsy.chuanmen.features.logs.LogsRoute
import com.github.zzwtsy.chuanmen.features.logs.LogsViewModel
import com.github.zzwtsy.chuanmen.features.overview.OverviewRoute
import com.github.zzwtsy.chuanmen.features.overview.OverviewViewModel
import com.github.zzwtsy.chuanmen.features.settings.SettingsRoute
import com.github.zzwtsy.chuanmen.features.settings.SettingsViewModel
import com.github.zzwtsy.chuanmen.lib.vpn.DataFreshness
import com.github.zzwtsy.chuanmen.ui.theme.ChuanmenTheme
import com.github.zzwtsy.chuanmen.ui.theme.Dimensions

/** 按 Activity 生命周期收集状态，不在组合中提交连接。 */
@Composable
fun ChuanmenApp(
    model: ChuanmenViewModel = viewModel(viewModelStoreOwner = LocalActivity.current as ComponentActivity, key = "app", factory = AppAssembly.factory),
    featureFactory: ViewModelProvider.Factory = AppAssembly.featureFactory(model.viewedProfileId, model.appearancePreferences),
) {
    val owner = LocalActivity.current as ComponentActivity
    val configuration: ConfigurationViewModel = viewModel(owner, key = "configuration", factory = featureFactory)
    val overview: OverviewViewModel = viewModel(owner, key = "overview", factory = featureFactory)
    val devices: DevicesViewModel = viewModel(owner, key = "devices", factory = featureFactory)
    val logs: LogsViewModel = viewModel(owner, key = "logs", factory = featureFactory)
    val settings: SettingsViewModel = viewModel(owner, key = "settings", factory = featureFactory)
    val state by model.state.collectAsStateWithLifecycle()
    val configurationState by configuration.state.collectAsStateWithLifecycle()
    val creation by configuration.creation.collectAsStateWithLifecycle()
    val creating = state.navigation.creating || state.profiles.isEmpty()
    var androidGroup by remember { mutableStateOf(false) }
    var policyTarget by remember { mutableStateOf<com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId?>(null) }
    fun cancelCreation() {
        if (creation?.let { it.saving || it.createdId != null } == true) return
        configuration.actCreation(CreationAction.Cancel)
        if (state.profiles.isEmpty()) owner.finish() else model.act(AppAction.CancelCreation)
    }
    BackHandler(creating) { cancelCreation() }
    LaunchedEffect(creating) { if (creating) configuration.actCreation(CreationAction.Begin) }
    LaunchedEffect(creation?.createdId, creation?.token) {
        creation?.let { draft -> draft.createdId?.let { id ->
            model.act(AppAction.FinishCreation(id))
            configuration.actCreation(CreationAction.Acknowledge(requireNotNull(draft.token)))
        } }
    }
    ChuanmenTheme(
        darkTheme = state.appearance.darkTheme ?: isSystemInDarkTheme(),
        dynamicColor = state.appearance.dynamicColor,
    ) {
        if (creating) {
            creation?.let { ConfigurationEntry(it, configuration::actCreation, if (state.profiles.isEmpty()) null else ::cancelCreation) }
        } else ChuanmenScreen(state, { action ->
            if (action == AppAction.CreateProfile) configuration.actCreation(CreationAction.Begin)
            if (action is AppAction.Navigate) androidGroup = false
            model.act(action)
        }) { destination, settingsOpen ->
            if (settingsOpen) SettingsRoute(settings, { policyTarget = state.navigation.viewedProfileId; androidGroup = true; model.act(AppAction.Navigate(Destination.Configuration)) }, configurationState.draft?.dirty == true)
            else when (destination) {
                Destination.Configuration -> ConfigurationRoute(configuration, androidGroup, policyTarget)
                Destination.Overview -> OverviewRoute(overview) { model.act(AppAction.Navigate(Destination.Devices)) }
                Destination.Devices -> DevicesRoute(devices)
                Destination.Logs -> LogsRoute(logs)
            }
        }
    }
}

/** 无数据源副作用的界面，调用方提供状态与操作回调。 */
@Composable
fun ChuanmenScreen(state: AppUiState, onAction: (AppAction) -> Unit, content: @Composable (Destination, Boolean) -> Unit) {
    var selectorOpen by remember { mutableStateOf(false) }
    BackHandler(state.navigation.settingsOpen || state.navigation.destination != Destination.Overview) {
        onAction(AppAction.Back)
    }
    Scaffold(
        topBar = {
            Surface {
                // 自定义顶栏自己避让系统栏/刘海，Surface 的背景仍延伸到系统栏后方。
                Column(
                    Modifier.fillMaxWidth().windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    ).padding(Dimensions.pagePadding),
                ) {
                    Text("串门", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.itemSpacing)) {
                        Column(Modifier.weight(1f)) {
                            Button(onClick = { selectorOpen = true }, enabled = state.profiles.isNotEmpty()) {
                                Text(state.viewed?.displayName ?: "没有网络配置")
                            }
                            DropdownMenu(selectorOpen, onDismissRequest = { selectorOpen = false }) {
                                DropdownMenuItem(text = { Text("新建配置") }, onClick = { selectorOpen = false; onAction(AppAction.CreateProfile) })
                                state.profiles.forEach { profile ->
                                    DropdownMenuItem(
                                        text = { Text(profile.displayName) },
                                        onClick = { selectorOpen = false; onAction(AppAction.ViewProfile(profile.profileId)) },
                                    )
                                }
                            }
                        }
                        TextButton(onClick = { onAction(AppAction.OpenSettings) }) { Text("设置") }
                    }
                    val running = state.running
                    if (running != null && running.profileId != state.navigation.viewedProfileId) {
                        val historical = running.freshness != DataFreshness.Fresh
                        Text(if (historical) "另一个网络的运行结果未确认：${running.displayName}"
                            else "另一个网络正在运行：${running.displayName}")
                        TextButton(onClick = { onAction(AppAction.ViewProfile(running.profileId)) }) {
                            Text(if (historical) "查看相关网络" else "查看运行网络")
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                Destination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = !state.navigation.settingsOpen && state.navigation.destination == destination,
                        onClick = { onAction(AppAction.Navigate(destination)) },
                        // 字符图标只作装饰，完整名称由 label 唯一提供给辅助技术。
                        icon = { Text(destination.title.take(1), Modifier.clearAndSetSemantics {}) },
                        label = { Text(destination.title) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Column(
            Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).imePadding()
                .verticalScroll(rememberScrollState()).padding(Dimensions.pagePadding),
            verticalArrangement = Arrangement.spacedBy(Dimensions.sectionSpacing),
        ) {
            content(state.navigation.destination, state.navigation.settingsOpen)
            if (state.navigation.settingsOpen) TextButton(onClick = { onAction(AppAction.Back) }) { Text("返回") }
        }
    }
}
