package com.github.zzwtsy.chuanmen.features.configuration.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.github.zzwtsy.chuanmen.components.SectionCard
import com.github.zzwtsy.chuanmen.features.configuration.lib.*

/** 目录决定显隐和控件；两个标签、搜索及节点列表共同编辑同一份原始输入。 */
@Composable
internal fun ConfigurationForm(
    mode: EditorMode, search: String, input: RawConfigurationInput, validation: InputValidation,
    onEdit: (DraftField, String) -> Unit, onPeer: (PeerAction) -> Unit, enabled: Boolean = true,
) {
    val fields = ConfigurationFields.all.filter { it.matches(search) && (mode == EditorMode.Full || it.basicVisible) }
    if (fields.isEmpty()) Text("没有匹配的配置项")
    fields.groupBy { it.group }.forEach { (group, entries) ->
        SectionCard(group) {
            entries.forEach { entry ->
                val field = entry.field
                val active = enabled && entry.enabled(input)
                when (entry.inputType) {
                    FieldInputType.ReadOnly -> Text(entry.label)
                    FieldInputType.PeerList -> PeerList(input.peers, validation.peerErrors, active, onPeer)
                    FieldInputType.Toggle -> {
                        val checked = input.value(requireNotNull(field)) == "true"
                        Row(Modifier.fillMaxWidth().toggleable(checked, enabled = active, role = Role.Checkbox,
                            onValueChange = { onEdit(field, it.toString()) })) {
                            Checkbox(checked, null, enabled = active)
                            Text(entry.label)
                        }
                    }
                    else -> OutlinedTextField(
                        value = input.value(requireNotNull(field)), onValueChange = { onEdit(field, it) },
                        label = { Text(entry.label) }, modifier = Modifier.fillMaxWidth(), enabled = active,
                        visualTransformation = if (entry.inputType == FieldInputType.Secret) PasswordVisualTransformation() else VisualTransformation.None,
                        isError = field in validation.errors,
                        supportingText = { Text(validation.errors[field] ?: entry.emptySemantics) },
                        singleLine = entry.inputType != FieldInputType.Multiline,
                    )
                }
            }
        }
    }
    if (mode == EditorMode.Basic) Text(if (input.automatic) "IPv4：自动分配"
        else input.rawIpv4.takeIf(String::isNotBlank)?.let { "IPv4：静态 $it" } ?: "IPv4：未指定")
    val hiddenErrors = validation.errors.filterKeys { field -> fields.none { it.field == field } }
    if (hiddenErrors.isNotEmpty()) Text("其他配置项存在错误：${hiddenErrors.values.joinToString("；")}", color = MaterialTheme.colorScheme.error)
}

/** 显式行操作保留节点身份；新增空行必须填写或删除，不能在保存时静默过滤。 */
@Composable
private fun PeerList(rows: List<PeerDraftRow>, errors: Map<PeerRowId, String>, enabled: Boolean, onAction: (PeerAction) -> Unit) {
    Text("连接节点")
    rows.forEachIndexed { index, row -> key(row.id) {
        OutlinedTextField(row.rawUri, { onAction(PeerAction.ReplaceUri(row.id, it)) },
            label = { Text("节点 URL ${index + 1}") }, enabled = enabled, singleLine = true,
            modifier = Modifier.fillMaxWidth(), isError = row.id in errors,
            supportingText = { Text(errors[row.id] ?: "tcp://host:port 或 udp://host:port") })
        Row {
            TextButton(onClick = { onAction(PeerAction.Remove(row.id)) }, enabled = enabled) { Text("删除节点 ${index + 1}") }
            TextButton(onClick = { onAction(PeerAction.Move(row.id, -1)) }, enabled = enabled && index > 0) { Text("上移 ${index + 1}") }
            TextButton(onClick = { onAction(PeerAction.Move(row.id, 1)) }, enabled = enabled && index < rows.lastIndex) { Text("下移 ${index + 1}") }
        }
    } }
    TextButton(onClick = { onAction(PeerAction.Add) }, enabled = enabled) { Text("添加节点") }
}
