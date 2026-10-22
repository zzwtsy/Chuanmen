package com.github.zzwtsy.chuanmen.utils

/** 不执行 DNS 查询的格式检查，避免配置校验触发网络或依赖宿主解析结果。 */
internal fun isIpv4Literal(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all {
        it.isNotEmpty() && it.length <= 3 && it.all { char -> char in '0'..'9' } &&
            (it.length == 1 || it.first() != '0') && (it.toIntOrNull() ?: -1) in 0..255
    }
}
