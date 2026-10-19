package com.github.zzwtsy.chuanmen.features.settings

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.zzwtsy.chuanmen.lib.Appearance

@Composable
internal fun SettingsRoute(model: SettingsViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    SettingsScreen(state, model::setAppearance)
}

@Composable
fun SettingsScreen(state: SettingsUiState, onChange: (Appearance) -> Unit) {
    Text("设置", style = MaterialTheme.typography.headlineSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("系统动态取色")
        Switch(state.appearance.dynamicColor, { onChange(state.appearance.copy(dynamicColor = it)) },
            modifier = Modifier.semantics { contentDescription = "系统动态取色" })
    }
    val dark = state.appearance.darkTheme ?: isSystemInDarkTheme()
    TextButton(onClick = { onChange(state.appearance.copy(darkTheme = !dark)) }) { Text("切换浅色 / 深色") }
    Text(state.internetScope)
}
