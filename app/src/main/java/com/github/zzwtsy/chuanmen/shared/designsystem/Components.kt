package com.github.zzwtsy.chuanmen.shared.designsystem

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.github.zzwtsy.chuanmen.shared.designsystem.theme.Dimensions

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

/** 指标值由数据所有者给出未知文案，此组件不以零代替缺测。 */
@Composable
fun DeviceStatistic(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 按决策分组，标题在语义树中标为 heading。 */
@Composable
fun ConfigurationGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(Dimensions.pagePadding),
            verticalArrangement = Arrangement.spacedBy(Dimensions.itemSpacing),
        ) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

/** 干净状态不生成操作横条，调用方明确决定待处理条件。 */
@Composable
fun ConditionalActionArea(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (visible) Column(verticalArrangement = Arrangement.spacedBy(Dimensions.itemSpacing), content = content)
}
