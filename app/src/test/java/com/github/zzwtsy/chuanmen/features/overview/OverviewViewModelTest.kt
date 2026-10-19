package com.github.zzwtsy.chuanmen.features.overview

import androidx.lifecycle.ViewModelStore
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.MemoryProfileRepository
import com.github.zzwtsy.chuanmen.lib.profile.ProfileSummary
import com.github.zzwtsy.chuanmen.lib.vpn.ConnectionPhase
import com.github.zzwtsy.chuanmen.lib.vpn.CorePeer
import com.github.zzwtsy.chuanmen.lib.vpn.CoreSnapshot
import com.github.zzwtsy.chuanmen.lib.vpn.DataFreshness
import com.github.zzwtsy.chuanmen.lib.vpn.Observation
import com.github.zzwtsy.chuanmen.lib.vpn.OperationFailure
import com.github.zzwtsy.chuanmen.lib.vpn.OperationKind
import com.github.zzwtsy.chuanmen.lib.vpn.OperationToken
import com.github.zzwtsy.chuanmen.lib.vpn.RecordingSessions
import com.github.zzwtsy.chuanmen.lib.vpn.RuntimeStatus
import com.github.zzwtsy.chuanmen.lib.vpn.SessionId
import com.github.zzwtsy.chuanmen.lib.vpn.SessionState
import com.github.zzwtsy.chuanmen.lib.vpn.TrafficPoint
import com.github.zzwtsy.chuanmen.lib.vpn.UnavailableReason
import com.github.zzwtsy.chuanmen.testAppliedSnapshot
import com.github.zzwtsy.chuanmen.testSavedRevision
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverviewViewModelTest {
    /** 同一 Fresh 会话保留缺测与零；换查看对象、失去新鲜度或换代后均不显示旧流量。 */
    @Test fun traffic_requiresMatchingCurrentAppliedSession() {
        val saved = testSavedRevision()
        val applied = testAppliedSnapshot(saved)
        val session = SessionState(ConnectionPhase.Running, applied.sessionId, saved.profileId, applied,
            traffic = listOf(TrafficPoint(1, null, null), TrafficPoint(2, 0.0, 12.0)))
        val current = OverviewViewModel.project(session, saved.profileId, true)
        assertEquals(listOf(null, 0.0), current.tx)
        assertEquals("0 B/s", current.upload)
        assertEquals("12 B/s", current.download)
        assertEquals("已连接", current.status)
        val core = CoreSnapshot(sessionId = applied.sessionId, lastOperation = OperationToken(applied.sessionId, 1), savedRevision = 1,
            status = RuntimeStatus.Running, ipv4 = null, peers = listOf(CorePeer(1, 0), CorePeer(2, 2)), routes = emptyList(),
            udpNatType = null, tcpNatType = null, attachmentId = null, attachmentState = null, counters = null,
            activeInstances = 1, callbacksInFlight = 0, jniGlobalRefs = 0, protectionCalls = 1, protectionFailures = 0)
        val measured = session.copy(core = Observation(core, 1, DataFreshness.Fresh))
        assertEquals("1", OverviewViewModel.project(measured, saved.profileId, true).onlineDevices)
        val oldId = SessionId("old-core", 2)
        listOf(measured.core!!.copy(freshness = DataFreshness.Stale),
            Observation(core.copy(sessionId = oldId, lastOperation = OperationToken(oldId, 1)), 1, DataFreshness.Fresh)).forEach { old ->
            assertEquals("未采样", OverviewViewModel.project(session.copy(core = old), saved.profileId, true).onlineDevices)
        }
        val other = OverviewViewModel.project(session, LocalProfileId("00000000-0000-4000-8000-000000000002"), true)
        assertNull(other.ip)
        assertFalse(other.showDisconnect)
        assertEquals("未连接", other.status)
        assertTrue(other.tx.isEmpty())
        listOf(
            session.copy(applied = applied.copy(freshness = DataFreshness.Stale)),
            session.copy(sessionId = SessionId("next", 2)),
            session.copy(phase = ConnectionPhase.OutcomeUnknown),
        ).forEach { stale ->
            val state = OverviewViewModel.project(stale, saved.profileId, true)
            assertTrue(state.tx.isEmpty())
            assertTrue(state.rx.isEmpty())
            assertEquals("暂无数据", state.upload)
            assertEquals("未采样", state.onlineDevices)
            assertTrue(state.historicalAddress)
        }
    }

    /** B 连接失败后查看 A 的历史运行内容仍可恢复；其他配置、已删除对象及非失败状态不提供恢复。 */
    @Test fun restore_requiresViewedHistoricalAppliedProfileAfterFailure() {
        val saved = testSavedRevision()
        val otherId = LocalProfileId("00000000-0000-4000-8000-000000000002")
        val applied = testAppliedSnapshot(saved).copy(freshness = DataFreshness.Stale)
        val failed = SessionState(
            phase = ConnectionPhase.Failed,
            sessionId = SessionId("failed-b", 2),
            requestedProfileId = otherId,
            applied = applied,
            failure = OperationFailure(OperationKind.Connect, UnavailableReason.EngineRejected),
        )
        val historical = OverviewViewModel.project(failed, saved.profileId, true)
        assertTrue(historical.canRestore)
        assertTrue(historical.historicalAddress)
        assertEquals(applied.virtualIpv4.value, historical.ip)
        assertNull(historical.failure)
        assertTrue(historical.tx.isEmpty())
        val other = OverviewViewModel.project(failed, otherId, true)
        assertFalse(other.canRestore)
        assertNull(other.ip)
        assertNotNull(other.failure)
        assertFalse(OverviewViewModel.project(failed, saved.profileId, false).canRestore)
        assertFalse(OverviewViewModel.project(failed, null, false).canRestore)
        assertFalse(OverviewViewModel.project(failed.copy(applied = null), saved.profileId, true).canRestore)
        ConnectionPhase.entries.filter { it != ConnectionPhase.Failed }.forEach { phase ->
            assertFalse(phase.name, OverviewViewModel.project(failed.copy(phase = phase), saved.profileId, true).canRestore)
        }
    }

    /** 查看 B 的失败后切回 A 并请求恢复，只调用同一客户端，保留两份已保存配置及本地名称。 */
    @Test fun restore_afterChangingViewedProfileDoesNotSaveOrConnect() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val appliedSaved = testSavedRevision()
            val saved = appliedSaved.copy(revision = 2, core = appliedSaved.core.copy(hostname = "saved-host"))
            val other = testSavedRevision(LocalProfileId("00000000-0000-4000-8000-000000000002"))
            val repository = MemoryProfileRepository(listOf(ProfileSummary(saved, "本地 A"), ProfileSummary(other, "本地 B")))
            val before = repository.profiles.value
            val sessions = RecordingSessions(SessionState(
                phase = ConnectionPhase.Failed,
                requestedProfileId = other.profileId,
                applied = testAppliedSnapshot(appliedSaved).copy(freshness = DataFreshness.Stale),
            ))
            val viewed = MutableStateFlow<LocalProfileId?>(other.profileId)
            val model = OverviewViewModel(repository, sessions, viewed)
            store.put("overview", model)
            // 启动 WhileSubscribed 上游，再消费查看对象切换后的投影。
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
            runCurrent()
            assertFalse(model.state.value.canRestore)
            viewed.value = saved.profileId
            runCurrent()
            assertTrue(model.state.value.canRestore)
            model.act(OverviewAction.RestoreRunning)
            runCurrent()
            assertEquals(1, sessions.restoreRequests)
            assertTrue(sessions.requests.isEmpty())
            assertEquals(before, repository.profiles.value)
            assertEquals(saved, repository.findSaved(saved.profileId))
            assertEquals(other, repository.findSaved(other.profileId))
        } finally {
            store.clear(); runCurrent(); Dispatchers.resetMain()
        }
    }

    /** 总览动作只调用注入的同一客户端；查看设备不发命令，拒绝反馈保留，换查看对象才清除。 */
    @Test fun commands_andFeedbackStayInOverview() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val saved = testSavedRevision()
            val repository = MemoryProfileRepository(listOf(ProfileSummary(saved, "A")))
            val sessions = RecordingSessions(SessionState())
            val viewed = MutableStateFlow<LocalProfileId?>(saved.profileId)
            val model = OverviewViewModel(repository, sessions, viewed)
            store.put("overview", model)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
            runCurrent()
            model.act(OverviewAction.Connect)
            model.act(OverviewAction.Disconnect)
            model.act(OverviewAction.RestoreRunning)
            runCurrent()
            assertEquals(listOf(OperationKind.Connect, OperationKind.Stop), sessions.requests)
            assertEquals(1, sessions.restoreRequests)
            val message = model.state.value.message
            assertTrue(message!!.startsWith("操作未执行："))
            model.act(OverviewAction.ViewDevices)
            runCurrent()
            assertEquals(2, sessions.requests.size)
            assertEquals(1, sessions.restoreRequests)
            assertEquals(message, model.state.value.message)
            viewed.value = null
            runCurrent()
            assertNull(model.state.value.message)
        } finally {
            store.clear(); runCurrent(); Dispatchers.resetMain()
        }
    }
}
