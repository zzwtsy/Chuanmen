package com.github.zzwtsy.chuanmen.feature.configuration.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.zzwtsy.chuanmen.contracts.Ipv4Assignment
import com.github.zzwtsy.chuanmen.contracts.OperationFailure
import com.github.zzwtsy.chuanmen.shared.designsystem.ConditionalActionArea
import com.github.zzwtsy.chuanmen.shared.designsystem.ConfigurationGroup
import com.github.zzwtsy.chuanmen.shared.designsystem.theme.Dimensions
import com.github.zzwtsy.chuanmen.shared.presentation.userMessage

sealed interface ConfigurationAction {
    data class Edit(val field: DraftField, val raw: String) : ConfigurationAction
    data class ChangeMode(val mode: EditorMode) : ConfigurationAction
    data object Rename : ConfigurationAction
    data object Save : ConfigurationAction
    data object Apply : ConfigurationAction
    data object Discard : ConfigurationAction
    data object Restore : ConfigurationAction
}

/** 首条编辑切片只渲染传入草稿；完整 EasyTier 配置表单在 P03 建立。 */
@Composable
fun ConfigurationScreen(
    draft: Draft?, pendingApplication: Boolean, operationPending: Boolean,
    failure: OperationFailure?, onAction: (ConfigurationAction) -> Unit,
) {
    if (draft == null) {
        Text("暂无配置")
        return
    }
    Text("配置", style = MaterialTheme.typography.headlineSmall)
    TextButton(onClick = { onAction(ConfigurationAction.Rename) }) { Text("修改本地名称") }
    Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.itemSpacing)) {
        EditorMode.entries.forEach { mode ->
            FilterChip(
                selected = draft.mode == mode, onClick = { onAction(ConfigurationAction.ChangeMode(mode)) },
                label = { Text(if (mode == EditorMode.Basic) "基础配置" else "完整配置") },
            )
        }
    }
    ConfigurationGroup("网络与地址") {
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
    if (draft.mode == EditorMode.Full) ConfigurationGroup("中继与性能") {
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
    ConditionalActionArea(draft.dirty || pendingApplication || failure != null) {
        if (draft.dirty) {
            Text("有未保存的更改")
            Button(onClick = { onAction(ConfigurationAction.Save) }, enabled = draft.errors.isEmpty()) { Text("仅保存") }
            TextButton(onClick = { onAction(ConfigurationAction.Discard) }) { Text("丢弃草稿") }
        }
        if (pendingApplication) {
            Text("更改待应用")
            Button(onClick = { onAction(ConfigurationAction.Apply) }, enabled = !operationPending && !draft.dirty) { Text("应用已保存配置") }
            TextButton(onClick = { onAction(ConfigurationAction.Restore) }) { Text("恢复运行配置到已保存") }
        }
        failure?.let { Text(it.userMessage(), color = MaterialTheme.colorScheme.error) }
    }
}
