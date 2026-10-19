package com.github.zzwtsy.chuanmen.application

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.github.zzwtsy.chuanmen.contracts.DataFreshness
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.zzwtsy.chuanmen.contracts.AndroidVpnPolicy
import com.github.zzwtsy.chuanmen.feature.configuration.edit.ConfigurationAction
import com.github.zzwtsy.chuanmen.feature.configuration.edit.ConfigurationScreen
import com.github.zzwtsy.chuanmen.feature.devices.DevicesScreen
import com.github.zzwtsy.chuanmen.feature.logs.LogsScreen
import com.github.zzwtsy.chuanmen.feature.overview.OverviewAction
import com.github.zzwtsy.chuanmen.feature.overview.OverviewScreen
import com.github.zzwtsy.chuanmen.feature.overview.OverviewUiState
import com.github.zzwtsy.chuanmen.shared.designsystem.theme.ChuanmenTheme
import com.github.zzwtsy.chuanmen.shared.designsystem.theme.Dimensions

/** flavor 决定装配；按 Activity 生命周期收集状态，不在组合中提交连接。 */
@Composable
fun ChuanmenApp(model: ChuanmenViewModel = viewModel(factory = AppAssembly.factory)) {
    val state by model.state.collectAsStateWithLifecycle()
    ChuanmenTheme(
        darkTheme = state.appearance.darkTheme ?: isSystemInDarkTheme(),
        dynamicColor = state.appearance.dynamicColor,
    ) { ChuanmenScreen(state, model::act) }
}

/** 无数据源副作用的界面，Preview 和设备测试直接传入 fixture 快照。 */
@Composable
fun ChuanmenScreen(state: AppUiState, onAction: (AppAction) -> Unit) {
    var selectorOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var renameRaw by rememberSaveable { mutableStateOf("") }
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
                    Text(if (state.readings.simulated) "演示 · 模拟数据" else "串门", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.itemSpacing)) {
                        Column(Modifier.weight(1f)) {
                            Button(onClick = { selectorOpen = true }, enabled = state.profiles.isNotEmpty()) {
                                Text(state.viewed?.displayName ?: "没有网络配置")
                            }
                            DropdownMenu(selectorOpen, onDismissRequest = { selectorOpen = false }) {
                                state.profiles.forEach { profile ->
                                    DropdownMenuItem(
                                        text = { Text(profile.displayName) },
                                        onClick = { selectorOpen = false; onAction(AppAction.ViewProfile(profile.saved.profileId)) },
                                    )
                                }
                            }
                        }
                        TextButton(onClick = { onAction(AppAction.OpenSettings) }) { Text("设置") }
                    }
                    val applied = state.session.applied
                    if (applied != null && applied.saved.profileId != state.navigation.viewedProfileId) {
                        val running = state.profiles.find { it.saved.profileId == applied.saved.profileId }
                        val historical = applied.freshness == DataFreshness.Stale
                        Text(if (historical) "另一个网络的运行结果未确认：${running?.displayName ?: "未知配置"}"
                            else "另一个网络正在运行：${running?.displayName ?: "未知配置"}")
                        TextButton(onClick = { onAction(AppAction.ViewProfile(applied.saved.profileId)) }) {
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
            if (state.navigation.settingsOpen) {
                SettingsContent(state.appearance, state.viewed?.saved?.vpnPolicy, state.readings.simulated) {
                    onAction(AppAction.SetAppearance(it))
                }
                TextButton(onClick = { onAction(AppAction.Back) }) { Text("返回") }
            } else when (state.navigation.destination) {
                Destination.Overview -> OverviewScreen(
                    OverviewUiState(state.session, state.navigation.viewedProfileId, state.viewed != null,
                        state.readings.txBytesPerSecond, state.readings.rxBytesPerSecond, state.readings.simulated),
                ) { action ->
                    onAction(when (action) {
                        OverviewAction.Connect -> AppAction.Connect
                        OverviewAction.Disconnect -> AppAction.Disconnect
                        OverviewAction.RestoreRunning -> AppAction.RestoreRunning
                        OverviewAction.ViewDevices -> AppAction.Navigate(Destination.Devices)
                    })
                }
                Destination.Devices -> DevicesScreen(state.session.applied.takeIf {
                    it?.saved?.profileId == state.navigation.viewedProfileId
                }, state.readings.simulated, state.session.core, state.session.buildInfo)
                Destination.Configuration -> ConfigurationScreen(
                    state.draft, state.pendingApplication, state.session.pending != null,
                    state.session.failure.takeIf { state.session.requestedProfileId == state.navigation.viewedProfileId },
                ) { action ->
                    when (action) {
                        ConfigurationAction.Rename -> { renameRaw = state.viewed?.displayName.orEmpty(); renameOpen = true }
                        else -> onAction(when (action) {
                            is ConfigurationAction.Edit -> AppAction.Edit(action.field, action.raw)
                            is ConfigurationAction.ChangeMode -> AppAction.ChangeEditorMode(action.mode)
                            ConfigurationAction.Save -> AppAction.Save
                            ConfigurationAction.Apply -> AppAction.Apply
                            ConfigurationAction.Discard -> AppAction.Discard
                            ConfigurationAction.Restore -> AppAction.RestoreApplied
                            ConfigurationAction.Rename -> error("改名已单独处理")
                        })
                    }
                }
                Destination.Logs -> LogsScreen(state.readings.logs)
            }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
    if (renameOpen) AlertDialog(
        onDismissRequest = { renameOpen = false }, title = { Text("本地配置名称") },
        text = {
            OutlinedTextField(
                value = renameRaw, onValueChange = { renameRaw = it },
                label = { Text("配置名称（可选）") }, singleLine = true,
                supportingText = { Text("仅用于本机识别，留空使用网络名称") },
            )
        },
        confirmButton = { TextButton(onClick = { onAction(AppAction.Rename(renameRaw)); renameOpen = false }) { Text("确认改名") } },
        dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("取消") } },
    )
}

@Composable
private fun SettingsContent(
    appearance: Appearance, policy: AndroidVpnPolicy?, simulated: Boolean, onChange: (Appearance) -> Unit,
) {
    Text("设置", style = MaterialTheme.typography.headlineSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("系统动态取色")
        Switch(
            checked = appearance.dynamicColor,
            onCheckedChange = { onChange(appearance.copy(dynamicColor = it)) },
            modifier = Modifier.semantics { contentDescription = "系统动态取色" },
        )
    }
    val dark = appearance.darkTheme ?: isSystemInDarkTheme()
    TextButton(onClick = { onChange(appearance.copy(darkTheme = !dark)) }) { Text("切换浅色 / 深色") }
    Text(when (policy?.includeInternet) {
        true -> if (simulated) "互联网流量：已开启（模拟）" else "互联网流量：已开启"
        false -> if (simulated) "互联网流量：未开启（模拟）" else "互联网流量：未开启"
        null -> "暂无网络策略"
    })
}
