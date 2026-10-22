package com.github.zzwtsy.chuanmen.components

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.github.zzwtsy.chuanmen.ui.theme.Dimensions

/** 总览和设备复用同一个复制动作；只有剪贴板写入成功才反馈。 */
@Composable
fun CopyIpButton(ip: String) {
    val context = LocalContext.current
    TextButton(
        onClick = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("IP", ip)) }
                .onSuccess {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                        Toast.makeText(context, "IP 已复制", Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure { Toast.makeText(context, "复制失败", Toast.LENGTH_SHORT).show() }
        },
        modifier = Modifier.heightIn(min = Dimensions.minimumTouchTarget),
    ) { Text("复制 IP") }
}
