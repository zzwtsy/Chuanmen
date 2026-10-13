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
    return "$action 失败：${reason.userMessage()}"
}

/** 说明当前条件和用户可采取的动作，不把拒绝或超时显示为成功。 */
fun UnavailableReason.userMessage(): String = when (this) {
    UnavailableReason.NoMeasurement -> "当前没有采样数据"
    UnavailableReason.NotImplemented -> "此能力尚未实现"
    UnavailableReason.TimedOut -> "等待结果超时，请重试"
    UnavailableReason.EngineRejected -> "核心未接受此次操作，请检查配置后重试"
    UnavailableReason.NoProfile -> "请选择已保存的网络配置"
    UnavailableReason.Busy -> "另一项操作正在进行，请稍后重试"
    UnavailableReason.DifferentRunningProfile -> "请先断开当前运行的网络"
    UnavailableReason.NotRunning -> "当前没有运行中的网络"
}
