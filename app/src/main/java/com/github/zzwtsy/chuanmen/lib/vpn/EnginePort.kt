package com.github.zzwtsy.chuanmen.lib.vpn

/** JNI 替换点仅用于桥内部测试；JSON 和原生句柄不能进入 UI。 */
internal fun interface EnginePort {
    fun request(json: String, callback: ProtectorCallback?): String
}

/** JNI 按固定签名调用；所有平台异常都转成拒绝，不能把异常原文带到诊断。 */
internal class ProtectorCallback(private val protector: SocketProtector) {
    @Suppress("unused")
    fun protectForNative(fd: Int, sessionId: String, generation: Long): Boolean =
        try { fd >= 0 && protector.protect(fd, SessionId(sessionId, generation)) }
        catch (_: Exception) { false }
}

internal object NativeBindings {
    init { System.loadLibrary("chuanmen_core") }
    @JvmStatic external fun request(json: String, callback: ProtectorCallback?): String
}

internal object JniEnginePort : EnginePort {
    override fun request(json: String, callback: ProtectorCallback?) = NativeBindings.request(json, callback)
}
