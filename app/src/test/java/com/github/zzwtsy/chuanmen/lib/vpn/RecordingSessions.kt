package com.github.zzwtsy.chuanmen.lib.vpn

import com.github.zzwtsy.chuanmen.lib.profile.SavedRevision
import kotlinx.coroutines.flow.MutableStateFlow

/** UI 测试的固定会话输入；记录命令但不模拟核心、授权或会话演进。 */
internal class RecordingSessions(initial: SessionState) : SessionController {
    override val state = MutableStateFlow(initial)
    val requests = mutableListOf<OperationKind>()
    private fun record(kind: OperationKind): OperationResult {
        requests += kind
        return OperationResult.Rejected(UnavailableReason.NotImplemented)
    }
    override fun connect(saved: SavedRevision) = record(OperationKind.Connect)
    override fun apply(saved: SavedRevision) = record(OperationKind.Apply)
    var restoreRequests = 0
    override fun restoreRunning(): OperationResult { restoreRequests++; return OperationResult.Rejected(UnavailableReason.NotImplemented) }
    override fun disconnect() = record(OperationKind.Stop)
}
