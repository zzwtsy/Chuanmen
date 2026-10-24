package com.github.zzwtsy.chuanmen.lib.profile

/** 测试前置必须真实提交成功；失败时直接终止该例，不把缺失值当成默认数据。 */
fun <T> WriteResult<T>.requireSuccess(): T = when (this) {
    is WriteResult.Success -> value
    is WriteResult.Failure -> error("测试写入失败：$error")
}
