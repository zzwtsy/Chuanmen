package com.github.zzwtsy.chuanmen.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import com.github.zzwtsy.chuanmen.ui.theme.Dimensions

/** 干净状态不生成操作横条，调用方明确决定待处理条件。 */
@Composable
fun ConditionalActionArea(visible: Boolean, content: @Composable ColumnScope.() -> Unit) {
    if (visible) Column(verticalArrangement = Arrangement.spacedBy(Dimensions.itemSpacing), content = content)
}
