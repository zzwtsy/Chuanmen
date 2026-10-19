package com.github.zzwtsy.chuanmen.application

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.github.zzwtsy.chuanmen.contracts.*
import com.github.zzwtsy.chuanmen.feature.configuration.controlled.ControlledProfileInput
import com.github.zzwtsy.chuanmen.shared.designsystem.theme.ChuanmenTheme
import java.util.UUID

/** 受控 liveDebug 启动配置只驻留内存；秘密不通过 Intent、日志或状态恢复存储传递。 */
@Composable
internal fun ControlledEntry(ready: (ProfileRepository) -> Unit) {
    var localName by remember { mutableStateOf("受控网络") }
    var networkName by remember { mutableStateOf("p02-controlled") }
    var secret by remember { mutableStateOf("") }
    var peers by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("10.222.0.2") }
    var automatic by remember { mutableStateOf(false) }
    var dns by remember { mutableStateOf("") }
    var internet by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    ChuanmenTheme {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("受控连接配置", style = MaterialTheme.typography.headlineSmall)
                Text("配置仅在本次进程内保留。请使用受控对端；点击连接后申请 VPN 授权。")
                OutlinedTextField(localName, { localName = it }, label = { Text("本地名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(networkName, { networkName = it }, label = { Text("网络名称") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(secret, { secret = it }, label = { Text("网络密码") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(peers, { peers = it }, label = { Text("对端，每行一个 tcp:// 或 udp:// 地址") }, modifier = Modifier.fillMaxWidth())
                Row { Checkbox(automatic, { automatic = it }); Text("由核心自动分配 IPv4") }
                if (!automatic) OutlinedTextField(address, { address = it }, label = { Text("静态 IPv4") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(dns, { dns = it }, label = { Text("受控 DNS IPv4，逗号分隔；可留空") }, modifier = Modifier.fillMaxWidth())
                Row { Checkbox(internet, { internet = it }); Text("覆盖默认路由，仅用于受控验证") }
                if (error) Text("请检查名称、密码、对端和 IPv4 格式。", color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    try {
                        val repository = ControlledProfileInput.create(LocalProfileId(UUID.randomUUID().toString()), CoreInstanceId(UUID.randomUUID().toString()),
                            localName.trim(), networkName.trim(), NetworkSecret.of(secret),
                            peers.lineSequence().map(String::trim).filter(String::isNotEmpty).map(::PeerEndpoint).toList(),
                            if (automatic) Ipv4Assignment.Automatic else Ipv4Assignment.Static(address.trim()),
                            dns.split(',').map(String::trim).filter(String::isNotEmpty), internet)
                        secret = ""
                        ready(repository)
                    } catch (_: IllegalArgumentException) { error = true }
                }) { Text("保存受控配置") }
            }
        }
    }
}
