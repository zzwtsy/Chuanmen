package com.github.zzwtsy.chuanmen.application

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.github.zzwtsy.chuanmen.contracts.ProfileRepository

/** 正式配置编辑与加密持久化归 P03；release 不包含受控实验输入或默认秘密。 */
@Composable
internal fun ControlledEntry(ready: (ProfileRepository) -> Unit) { Text("尚未提供连接配置。") }
