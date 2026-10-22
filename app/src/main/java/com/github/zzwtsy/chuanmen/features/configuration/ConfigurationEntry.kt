package com.github.zzwtsy.chuanmen.features.configuration

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.github.zzwtsy.chuanmen.features.configuration.lib.ConfigurationInput
import com.github.zzwtsy.chuanmen.lib.profile.CoreInstanceId
import com.github.zzwtsy.chuanmen.lib.profile.Ipv4Assignment
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.NetworkSecret
import com.github.zzwtsy.chuanmen.lib.profile.PeerEndpoint
import com.github.zzwtsy.chuanmen.lib.profile.ProfileRepository
import com.github.zzwtsy.chuanmen.ui.theme.ChuanmenTheme
import java.util.UUID

/** 唯一连接入口；秘密仅驻留当前组合，不进入 Intent、日志或状态恢复存储。 */
@Composable
internal fun ConfigurationEntry(ready: (ProfileRepository) -> Unit) {
    var localName by remember { mutableStateOf("") }
    var networkName by remember { mutableStateOf("") }
    var secret by remember { mutableStateOf("") }
    var peers by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var automatic by remember { mutableStateOf(true) }
    var dns by remember { mutableStateOf("") }
    var internet by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    ChuanmenTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("连接配置", style = MaterialTheme.typography.headlineSmall)
                Text("配置仅在本次进程内保留。保存后可点击连接并申请 VPN 授权。")
                OutlinedTextField(localName, { localName = it }, label = { Text("本地名称（可选）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(networkName, { networkName = it }, label = { Text("网络名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text("网络密码") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(peers, { peers = it }, label = { Text("连接节点") }, supportingText = { Text("每行一个 tcp:// 或 udp:// 地址") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().toggleable(automatic, role = Role.Checkbox, onValueChange = { automatic = it })) {
                    Checkbox(automatic, onCheckedChange = null)
                    Text("自动分配 IPv4")
                }
                if (!automatic) OutlinedTextField(address, { address = it }, label = { Text("静态 IPv4") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dns, { dns = it }, label = { Text("DNS IPv4") }, supportingText = { Text("逗号分隔；可留空") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().toggleable(internet, role = Role.Checkbox, onValueChange = { internet = it })) {
                    Checkbox(internet, onCheckedChange = null)
                    Text("包含互联网流量")
                }
                if (error) Text("请检查网络名称、密码、连接节点和 IPv4 格式。", color = MaterialTheme.colorScheme.error)
                Button(enabled = !submitted, onClick = {
                    // 排队的第二次点击也可能在重组前到达；只允许发布一次仓库。
                    if (submitted) return@Button
                    val repository = try {
                        ConfigurationInput.create(
                            LocalProfileId(UUID.randomUUID().toString()), CoreInstanceId(UUID.randomUUID().toString()),
                            localName.trim(), networkName.trim(), NetworkSecret.of(secret),
                            peers.lineSequence().map(String::trim).filter(String::isNotEmpty).map(::PeerEndpoint).toList(),
                            if (automatic) Ipv4Assignment.Automatic else Ipv4Assignment.Static(address.trim()),
                            dns.split(',').map(String::trim).filter(String::isNotEmpty), internet,
                        )
                    } catch (_: IllegalArgumentException) {
                        error = true
                        return@Button
                    }
                    submitted = true
                    secret = ""
                    ready(repository)
                }) { Text("保存配置") }
            }
        }
    }
}
