package com.github.zzwtsy.chuanmen.features.configuration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.zzwtsy.chuanmen.features.configuration.components.ConfigurationForm
import com.github.zzwtsy.chuanmen.features.configuration.lib.*

/** 无仓库副作用的创建表单；输入由 Activity ViewModel 保留，首次提交中锁定表单。 */
@Composable
internal fun ConfigurationEntry(state: CreationDraft, onAction: (CreationAction) -> Unit, onCancel: (() -> Unit)? = null) {
    val enabled = !state.saving && state.createdId == null
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("新建配置", style = MaterialTheme.typography.headlineSmall)
            Text("配置加密保存在本机。保存后可点击连接并申请 VPN 授权。")
            OutlinedTextField(state.localName, { onAction(CreationAction.Name(it)) }, enabled = enabled,
                label = { Text("本地名称（可选）") }, modifier = Modifier.fillMaxWidth())
            Row { EditorMode.entries.forEach { mode -> FilterChip(state.mode == mode, { onAction(CreationAction.Mode(mode)) },
                enabled = enabled, label = { Text(if (mode == EditorMode.Basic) "基础配置" else "完整配置") }) } }
            if (state.mode == EditorMode.Full) OutlinedTextField(state.search, { onAction(CreationAction.Search(it)) }, enabled = enabled,
                label = { Text("搜索配置项") }, trailingIcon = { TextButton(onClick = { onAction(CreationAction.Search("")) }, enabled = enabled) { Text("清空") } })
            ConfigurationForm(state.mode, if (state.mode == EditorMode.Full) state.search else "", state.input, state.validation,
                { field, raw -> onAction(CreationAction.Edit(field, raw)) }, { onAction(CreationAction.Peer(it)) }, enabled)
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { onAction(CreationAction.Save) }, enabled = enabled) { Text(if (state.saving) "正在保存…" else "保存配置") }
            if (onCancel != null) TextButton(onClick = onCancel, enabled = enabled) { Text("取消创建") }
        }
    }
}
