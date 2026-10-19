package com.github.zzwtsy.chuanmen.shared.presentation

import com.github.zzwtsy.chuanmen.contracts.OperationFailure
import com.github.zzwtsy.chuanmen.contracts.OperationKind
import com.github.zzwtsy.chuanmen.contracts.UnavailableReason

/** 多个页面共用可操作的错误文案；领域枚举保留协议身份，不直接作为产品提示。 */
fun OperationFailure.userMessage(): String {
    val action = when (kind) {
        OperationKind.Connect -> "连接"
        OperationKind.Apply -> "应用配置"
        OperationKind.Stop -> "断开"
    }
    return if (reason in setOf(UnavailableReason.ResultUnknown, UnavailableReason.TimedOut)) {
        "$action 结果未确认：${reason.userMessage()}"
    } else "$action 失败：${reason.userMessage()}"
}

/** 说明当前条件和用户可采取的动作，不把拒绝或超时显示为成功。 */
fun UnavailableReason.userMessage(): String = when (this) {
    UnavailableReason.NoMeasurement -> "当前没有采样数据"
    UnavailableReason.NotImplemented -> "此能力尚未实现"
    UnavailableReason.TimedOut -> "等待确认超时，请先核对状态或停止"
    UnavailableReason.EngineRejected -> "核心未接受此次操作，请检查配置后重试"
    UnavailableReason.NoProfile -> "请选择已保存的网络配置"
    UnavailableReason.Busy -> "另一项操作正在进行，请稍后重试"
    UnavailableReason.DifferentRunningProfile -> "请先断开当前运行的网络"
    UnavailableReason.NotRunning -> "当前没有运行中的网络"
    UnavailableReason.PermissionDenied -> "VPN 授权被拒绝"
    UnavailableReason.ServiceRevoked -> "系统已撤销 VPN 授权"
    UnavailableReason.InvalidConfig -> "配置无效，请检查输入"
    UnavailableReason.ProtectionFailed -> "底层连接保护失败"
    UnavailableReason.AddressUnavailable -> "核心尚未获得可用地址"
    UnavailableReason.TunInstallFailed -> "VPN 接口安装失败"
    UnavailableReason.ProtocolMismatch -> "核心桥协议版本不兼容"
    UnavailableReason.ResultUnknown -> "保留停止入口，等待实际状态核对"
}
