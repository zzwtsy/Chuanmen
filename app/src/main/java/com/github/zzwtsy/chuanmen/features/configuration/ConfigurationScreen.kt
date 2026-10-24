package com.github.zzwtsy.chuanmen.features.configuration

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.zzwtsy.chuanmen.components.ConditionalActionArea
import com.github.zzwtsy.chuanmen.features.configuration.components.*
import com.github.zzwtsy.chuanmen.features.configuration.lib.*
import com.github.zzwtsy.chuanmen.lib.vpn.userMessage
import com.github.zzwtsy.chuanmen.ui.theme.Dimensions

sealed interface ConfigurationAction {
    data class Edit(val field: DraftField, val raw: String) : ConfigurationAction {
        override fun toString() = "Edit(field=$field, raw=<redacted>)"
    }
    data class Peer(val action: PeerAction) : ConfigurationAction
    data class Search(val raw: String) : ConfigurationAction
    data class ChangeMode(val mode: EditorMode) : ConfigurationAction
    data class Rename(val name: String) : ConfigurationAction
    data object Save : ConfigurationAction
    data object Apply : ConfigurationAction
    data object Discard : ConfigurationAction
    data object Restore : ConfigurationAction
    data object Reload : ConfigurationAction
    data object KeepDraft : ConfigurationAction
    data object Delete : ConfigurationAction
    data object Query : ConfigurationAction
    data object Stop : ConfigurationAction
    data object ClearMessage : ConfigurationAction
}

/** 设置跳转仍使用同一 ViewModel，只改变当前草稿的标签和搜索定位。 */
@Composable
internal fun ConfigurationRoute(model: ConfigurationViewModel, androidGroup: Boolean = false, targetId: com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId? = null) {
    val state by model.state.collectAsStateWithLifecycle()
    if (androidGroup && (targetId == null || state.profile?.saved?.profileId != targetId)) {
        Text("目标配置已不存在，请创建或选择配置")
        return
    }
    LaunchedEffect(androidGroup, state.profile?.saved?.profileId) {
        if (androidGroup) model.act(ConfigurationAction.ChangeMode(EditorMode.Full))
    }
    ConfigurationScreen(state, if (androidGroup) "android." else "", model::act)
}

/** 页面只提交显式动作；删除和冲突重载均须先确认目标。 */
@Composable
fun ConfigurationScreen(state: ConfigurationUiState, initialSearch: String = "", onAction: (ConfigurationAction) -> Unit) {
    val draft = state.draft
    val id = state.profile?.saved?.profileId?.value
    var renameOpen by rememberSaveable(id) { mutableStateOf(false) }
    var renameRaw by rememberSaveable(id) { mutableStateOf("") }
    var confirm by remember(id) { mutableStateOf<ConfigurationAction?>(null) }
    LaunchedEffect(id, initialSearch) { if (initialSearch.isNotEmpty()) onAction(ConfigurationAction.Search(initialSearch)) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        state.message?.let {
            if (it != state.draft?.saveFailure) snackbar.showSnackbar(it)
            onAction(ConfigurationAction.ClearMessage)
        }
    }
    if (renameOpen) RenameProfileDialog(renameRaw, { renameRaw = it }, {
        onAction(ConfigurationAction.Rename(renameRaw)); renameOpen = false
    }, { renameOpen = false })
    confirm?.let { action -> AlertDialog(
        onDismissRequest = { confirm = null },
        title = { Text(if (action == ConfigurationAction.Delete) "删除配置" else "丢弃当前草稿") },
        text = { Text(if (action == ConfigurationAction.Delete)
            "删除“${state.profile?.displayName}”？${if (state.holdingProfile) "正在运行或处理中，将先停止并等待确认。" else "当前未运行。"}"
            else "当前未保存的输入将被已保存版本替换。") },
        confirmButton = { TextButton(onClick = { confirm = null; onAction(action) }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
    ) }
    if (draft == null) { Text("暂无配置"); return }
    Text("配置", style = MaterialTheme.typography.headlineSmall)
    Row {
        TextButton(onClick = { renameRaw = state.profile?.metadata?.localDisplayName.orEmpty(); renameOpen = true }) { Text("修改本地名称") }
        TextButton(onClick = { confirm = ConfigurationAction.Delete }, enabled = !draft.saving) { Text("删除配置") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.itemSpacing)) {
        EditorMode.entries.forEach { mode -> FilterChip(draft.mode == mode,
            onClick = { onAction(ConfigurationAction.ChangeMode(mode)) }, label = { Text(if (mode == EditorMode.Basic) "基础配置" else "完整配置") }) }
    }
    if (draft.mode == EditorMode.Full) OutlinedTextField(draft.search, { onAction(ConfigurationAction.Search(it)) }, label = { Text("搜索配置项") },
        trailingIcon = { if (draft.search.isNotEmpty()) TextButton(onClick = { onAction(ConfigurationAction.Search("")) }) { Text("清空") } }, modifier = Modifier.fillMaxWidth())
    ConfigurationForm(draft.mode, if (draft.mode == EditorMode.Full) draft.search else "", draft.input, draft.validation,
        { field, raw -> onAction(ConfigurationAction.Edit(field, raw)) }, { onAction(ConfigurationAction.Peer(it)) })
    if (draft.base.document.unsupportedFields.isNotEmpty()) Text("包含未接入参数，可保存但不能运行：${draft.base.document.unsupportedFields.joinToString()}")
    if (draft.conflict) {
        Text("已保存版本已变化，草稿未覆盖")
        TextButton(onClick = { confirm = ConfigurationAction.Reload }, enabled = !draft.saving) { Text("重新载入已保存版本") }
        TextButton(onClick = { onAction(ConfigurationAction.KeepDraft) }) { Text("保留当前草稿") }
    }
    draft.saveFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    ConditionalActionArea(draft.dirty || state.pendingApplication || state.failure != null || state.outcomeUnknown) {
        if (draft.dirty) {
            Text("有未保存的更改")
            Button(onClick = { onAction(ConfigurationAction.Save) }, enabled = draft.errors.isEmpty() && !draft.saving && !draft.conflict) { Text(if (draft.saving) "正在保存…" else "仅保存") }
            TextButton(onClick = { confirm = ConfigurationAction.Discard }, enabled = !draft.saving) { Text("丢弃草稿") }
        }
        if (state.pendingApplication) {
            Text("更改待应用")
            Button(onClick = { onAction(ConfigurationAction.Apply) }, enabled = !state.operationPending && !state.outcomeUnknown && !draft.dirty && !draft.saving && !draft.conflict) { Text("应用已保存配置") }
        }
        if (state.canRestore) TextButton(onClick = { onAction(ConfigurationAction.Restore) }, enabled = !state.operationPending && !state.outcomeUnknown) { Text("恢复上次运行版本") }
        state.failure?.let { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) }
        if (state.outcomeUnknown) {
            Text("运行结果尚未确认")
            TextButton(onClick = { onAction(ConfigurationAction.Query) }) { Text("重新查询") }
            TextButton(onClick = { onAction(ConfigurationAction.Stop) }) { Text("显式停止") }
        }
    }
    SnackbarHost(snackbar)
}
