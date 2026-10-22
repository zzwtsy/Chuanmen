package com.github.zzwtsy.chuanmen.features.configuration

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.zzwtsy.chuanmen.components.ConditionalActionArea
import com.github.zzwtsy.chuanmen.components.SectionCard
import com.github.zzwtsy.chuanmen.features.configuration.components.RenameProfileDialog
import com.github.zzwtsy.chuanmen.features.configuration.lib.DraftField
import com.github.zzwtsy.chuanmen.features.configuration.lib.EditorMode
import com.github.zzwtsy.chuanmen.lib.profile.Ipv4Assignment
import com.github.zzwtsy.chuanmen.lib.vpn.userMessage
import com.github.zzwtsy.chuanmen.ui.theme.Dimensions

sealed interface ConfigurationAction {
    data class Edit(val field: DraftField, val raw: String) : ConfigurationAction
    data class ChangeMode(val mode: EditorMode) : ConfigurationAction
    data class Rename(val name: String) : ConfigurationAction
    data object Save : ConfigurationAction
    data object Apply : ConfigurationAction
    data object Discard : ConfigurationAction
    data object Restore : ConfigurationAction
}

/** 按 Activity 功能状态收集；重组不提交保存或应用。 */
@Composable
internal fun ConfigurationRoute(model: ConfigurationViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    ConfigurationScreen(state, model::act)
}

/** 只渲染功能投影；弹窗输入属于当前配置页的临时 UI 状态。 */
@Composable
fun ConfigurationScreen(state: ConfigurationUiState, onAction: (ConfigurationAction) -> Unit) {
    val draft = state.draft
    var renameOpen by rememberSaveable(state.profile?.saved?.profileId?.value) { mutableStateOf(false) }
    var renameRaw by rememberSaveable(state.profile?.saved?.profileId?.value) { mutableStateOf("") }
    if (renameOpen) RenameProfileDialog(renameRaw, { renameRaw = it }, {
        onAction(ConfigurationAction.Rename(renameRaw)); renameOpen = false
    }, { renameOpen = false })
    if (draft == null) {
        Text("暂无配置")
        return
    }
    Text("配置", style = MaterialTheme.typography.headlineSmall)
    TextButton(onClick = { renameRaw = state.profile?.displayName.orEmpty(); renameOpen = true }) { Text("修改本地名称") }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.itemSpacing)) {
        EditorMode.entries.forEach { mode ->
            FilterChip(
                selected = draft.mode == mode, onClick = { onAction(ConfigurationAction.ChangeMode(mode)) },
                label = { Text(if (mode == EditorMode.Basic) "基础配置" else "完整配置") },
            )
        }
    }
    SectionCard("网络与地址") {
        OutlinedTextField(
            value = draft.rawNetworkName,
            onValueChange = { onAction(ConfigurationAction.Edit(DraftField.NetworkName, it)) },
            modifier = Modifier.fillMaxWidth(), label = { Text("网络名称") },
            isError = DraftField.NetworkName in draft.errors,
            supportingText = { draft.errors[DraftField.NetworkName]?.let { Text(it) } }, singleLine = true,
        )
        Text(when (val ipv4 = draft.base.core.ipv4) {
            Ipv4Assignment.Automatic -> "IPv4：自动分配"
            is Ipv4Assignment.Static -> "IPv4：静态 ${ipv4.address}"
        })
    }
    if (draft.mode == EditorMode.Full) SectionCard("中继与性能") {
        OutlinedTextField(
            value = draft.rawMtu, onValueChange = { onAction(ConfigurationAction.Edit(DraftField.Mtu, it)) },
            modifier = Modifier.fillMaxWidth(), label = { Text("MTU") },
            isError = DraftField.Mtu in draft.errors,
            supportingText = { draft.errors[DraftField.Mtu]?.let { Text(it) } }, singleLine = true,
        )
    }
    if (draft.mode == EditorMode.Basic && DraftField.Mtu in draft.errors) {
        Text("完整配置中存在错误：${draft.errors[DraftField.Mtu]}", color = MaterialTheme.colorScheme.error)
    }
    ConditionalActionArea(draft.dirty || state.pendingApplication || state.failure != null) {
        if (draft.dirty) {
            Text("有未保存的更改")
            Button(onClick = { onAction(ConfigurationAction.Save) }, enabled = draft.errors.isEmpty()) { Text("仅保存") }
            TextButton(onClick = { onAction(ConfigurationAction.Discard) }) { Text("丢弃草稿") }
        }
        if (state.pendingApplication) {
            Text("更改待应用")
            Button(onClick = { onAction(ConfigurationAction.Apply) }, enabled = !state.operationPending && !draft.dirty) { Text("应用已保存配置") }
            TextButton(onClick = { onAction(ConfigurationAction.Restore) }) { Text("恢复运行配置到已保存") }
        }
        state.failure?.let { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) }
    }
    state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
}
