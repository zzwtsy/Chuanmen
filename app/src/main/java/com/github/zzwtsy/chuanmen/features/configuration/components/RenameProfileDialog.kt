package com.github.zzwtsy.chuanmen.features.configuration.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** 本地名称只通过确认回调提交；取消不修改仓库。 */
@Composable
internal fun RenameProfileDialog(raw: String, onChange: (String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("本地配置名称") },
        text = {
            OutlinedTextField(raw, onChange, label = { Text("配置名称（可选）") }, singleLine = true,
                supportingText = { Text("仅用于本机识别，留空使用网络名称") })
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("确认改名") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
