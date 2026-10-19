package com.github.zzwtsy.chuanmen.vpn

import com.github.zzwtsy.chuanmen.contracts.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class VpnSessionCoordinatorTest {
    private class TestEngine(private val order: MutableList<String>) : CoreEngine {
        override val capabilities = setOf(Capability.Connect, Capability.TunCounters)
        val commands = mutableListOf<EngineCommand>()
        private val queue = Channel<EngineEvent>(Channel.UNLIMITED)
        override val events = queue.receiveAsFlow()
        var valid: ConfirmationResult<Unit> = ConfirmationResult.Confirmed(Unit)
        var release: ConfirmationResult<Unit> = ConfirmationResult.Confirmed(Unit)
        override fun submit(command: EngineCommand) { commands += command }
        override suspend fun validate(saved: SavedRevision) = valid
        override suspend fun releaseSession(token: OperationToken): ConfirmationResult<Unit> {
            order += "jni-release"
            return release
        }
        fun emit(event: EngineEvent) { check(queue.trySend(event).isSuccess) }
    }
    private class TestPlatform(val order: MutableList<String>) : TunPlatform {
        override var authorized = true
        override var networkAvailable = true
        val descriptors = mutableListOf<TestTun>()
        val specs = mutableListOf<VpnInterfaceSpec>()
        var allowed: SessionId? = null
        override val protector = SocketProtector { _, id -> id == allowed }
        override fun foreground() = true
        override fun leaveForeground() { order += "foreground-end" }
        override fun allowProtection(sessionId: SessionId) { allowed = sessionId }
        override fun denyProtection() { allowed = null }
        override fun establish(spec: VpnInterfaceSpec): OwnedTun {
            specs += spec
            order += "establish"
            return TestTun(10 + descriptors.size, order).also(descriptors::add)
        }
    }
    private class TestTun(override val fd: Int, private val order: MutableList<String>) : OwnedTun {
        var closed = false
        override fun close() { check(!closed); closed = true; order += "pfd-close" }
    }
    private class PausedDispatcher : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks += block }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private inner class Harness(val test: TestScope, io: CoroutineDispatcher = StandardTestDispatcher(test.testScheduler)) {
        val order = mutableListOf<String>()
        val engine = TestEngine(order)
        val platform = TestPlatform(order)
        val coordinator = VpnSessionCoordinator(engine, platform, test.backgroundScope,
            MonotonicClock { test.testScheduler.currentTime }, SessionTimeouts(300, 300, 50, 100), io)
        val state get() = coordinator.state.value
        fun start(): EngineCommand.Start {
            assertTrue(coordinator.connect(saved()) is OperationResult.Accepted)
            test.runCurrent()
            return engine.commands.last() as EngineCommand.Start
        }
        fun coreStarted(start: EngineCommand.Start) {
            engine.emit(EngineEvent.CoreStarted(start.token, start.saved.revision))
            test.runCurrent()
        }
        fun query(address: String? = "10.42.0.9", attachment: AttachmentId? = null, lastOperation: OperationToken? = null, failures: Long = 0, status: RuntimeStatus = RuntimeStatus.Running) {
            val query = engine.commands.last { it is EngineCommand.QueryRuntime } as EngineCommand.QueryRuntime
            val session = query.token.sessionId
            engine.emit(EngineEvent.CoreObserved(query.token, CoreSnapshot(session,
                lastOperation ?: OperationToken(session, 1), (engine.commands.last { it is EngineCommand.Start } as EngineCommand.Start).saved.revision, status,
                address?.let { Ipv4Cidr(it, 24) }, emptyList(), emptyList(), null, null, attachment,
                attachment?.let { TunBorrowState.Installed }, null, 1, 0, 1, 1, failures)))
            test.runCurrent()
        }
        fun attach(): EngineCommand.InstallTun {
            val install = engine.commands.last { it is EngineCommand.InstallTun } as EngineCommand.InstallTun
            engine.emit(EngineEvent.TunAttached(install.token, TunInstalled(install.attachmentId, install.spec)))
            test.runCurrent()
            return install
        }
        fun connected(): EngineCommand.InstallTun { val start = start(); coreStarted(start); query(); return attach() }
        fun stopConfirmed() {
            val stop = engine.commands.last { it is EngineCommand.Stop } as EngineCommand.Stop
            order += "native-drained"
            engine.emit(EngineEvent.Stopped(stop.token, StopConfirmation(stop.token.sessionId, true)))
            test.runCurrent()
        }
    }

    /** 核心启动完成但地址未分配时继续等待，不创建 TUN 或发布 Running。 */
    @Test fun coreStartWithoutAddressDoesNotPrepareVpn() = runTest {
        val h = Harness(this); val start = h.start(); h.coreStarted(start); h.query(null)
        assertEquals(ConnectionPhase.AwaitingAddress, h.state.phase)
        assertTrue(h.platform.descriptors.isEmpty())
    }

    /** 使用核心实际地址而非静态输入；只有 TUN 安装确认后才发布 Running。 */
    @Test fun runningRequiresActualAddressAndTunConfirmation() = runTest {
        val h = Harness(this); val start = h.start(); h.coreStarted(start); h.query()
        assertEquals(ConnectionPhase.PreparingTun, h.state.phase)
        assertNull(h.state.applied)
        assertEquals("10.42.0.9", h.platform.specs.single().addresses.single().address)
        h.attach()
        assertEquals(ConnectionPhase.Running, h.state.phase)
        assertEquals("10.42.0.9", h.state.applied!!.virtualIpv4.value)
    }

    /** 停止超时保留 PFD 并阻止启动；迟到排空确认后按 PFD、JNI 的顺序释放。 */
    @Test fun lateStopReceiptReleasesRetainedResourcesInOrder() = runTest {
        val h = Harness(this); h.connected(); h.coordinator.disconnect()
        advanceTimeBy(101); runCurrent()
        assertEquals(ConnectionPhase.OutcomeUnknown, h.state.phase)
        assertFalse(h.platform.descriptors.single().closed)
        assertEquals(OperationResult.Rejected(UnavailableReason.Busy), h.coordinator.connect(saved()))
        h.stopConfirmed()
        assertEquals(ConnectionPhase.Idle, h.state.phase)
        assertTrue(h.order.indexOf("native-drained") < h.order.indexOf("pfd-close"))
        assertTrue(h.order.indexOf("pfd-close") < h.order.indexOf("jni-release"))
    }

    /** 用户重复停止共用正在进行的清理，不能重复提交或提前关闭句柄。 */
    @Test fun repeatedStopSharesPendingCleanup() = runTest {
        val h = Harness(this); h.connected()
        assertTrue(h.coordinator.disconnect() is OperationResult.Accepted)
        assertEquals(OperationResult.Unchanged, h.coordinator.disconnect())
        assertEquals(1, h.engine.commands.count { it is EngineCommand.Stop })
        assertFalse(h.platform.descriptors.single().closed)
    }

    /** 安装回执丢失时先标记历史并查询；匹配实际 attachment 与操作后恢复 Running。 */
    @Test fun lostInstallReceiptIsReconciledFromActualSnapshot() = runTest {
        val h = Harness(this); val start = h.start(); h.coreStarted(start); h.query()
        val install = h.engine.commands.last() as EngineCommand.InstallTun
        advanceTimeBy(51); runCurrent()
        assertEquals(ConnectionPhase.OutcomeUnknown, h.state.phase)
        assertFalse(h.platform.descriptors.single().closed)
        h.query(attachment = install.attachmentId, lastOperation = install.token)
        assertEquals(ConnectionPhase.Running, h.state.phase)
    }

    /** 安装回执丢失且核心状态未知时，即使 attachment 匹配也不能发布 Running 或关闭借用。 */
    @Test fun unknownRuntimeCannotConfirmLostTunInstallation() = runTest {
        val h = Harness(this); val start = h.start(); h.coreStarted(start); h.query()
        val install = h.engine.commands.last() as EngineCommand.InstallTun
        advanceTimeBy(51); runCurrent()
        h.query(attachment = install.attachmentId, lastOperation = install.token, status = RuntimeStatus.Unknown)
        assertEquals(ConnectionPhase.OutcomeUnknown, h.state.phase)
        assertFalse(h.platform.descriptors.single().closed)
        assertNull(h.state.applied)
    }

    /** 移除回执丢失时未知快照不能终止借用；核对预算耗尽后统一停止并等待排空才关闭。 */
    @Test fun unknownRuntimeCannotConfirmLostTunRemoval() = runTest {
        val h = Harness(this); val installed = h.connected()
        h.query(address = "10.42.1.9", attachment = installed.attachmentId, lastOperation = installed.token)
        assertTrue(h.engine.commands.last() is EngineCommand.RemoveTun)
        advanceTimeBy(51); runCurrent()
        h.query(address = "10.42.1.9", status = RuntimeStatus.Unknown)
        assertEquals(ConnectionPhase.OutcomeUnknown, h.state.phase)
        assertFalse(h.platform.descriptors.single().closed)
        advanceTimeBy(51); runCurrent()
        assertEquals(ConnectionPhase.Stopping, h.state.phase)
        assertFalse(h.platform.descriptors.single().closed)
        h.stopConfirmed()
        assertTrue(h.platform.descriptors.first().closed)
        assertEquals(1, h.platform.descriptors.size)
    }

    /** 移除回执丢失时同一核心确认仍运行且无 attachment，可安全关闭旧 PFD 并安装新 TUN。 */
    @Test fun lostRemovalIsReconciledFromConfirmedRunningSnapshot() = runTest {
        val h = Harness(this); val installed = h.connected()
        h.query(address = "10.42.1.9", attachment = installed.attachmentId, lastOperation = installed.token)
        advanceTimeBy(51); runCurrent()
        h.query(address = "10.42.1.9")
        assertTrue(h.platform.descriptors.first().closed)
        assertEquals(2, h.platform.descriptors.size)
        assertTrue(h.engine.commands.last() is EngineCommand.InstallTun)
    }

    /** 网络消失保留运行意图，旧会话确认停止后等待恢复；恢复时递增会话代次。 */
    @Test fun networkRecoveryWaitsForPreviousStop() = runTest {
        val h = Harness(this); val install = h.connected()
        h.platform.networkAvailable = false; h.coordinator.networkChanged()
        h.stopConfirmed()
        assertEquals(ConnectionPhase.Reconnecting, h.state.phase)
        h.platform.networkAvailable = true; h.coordinator.networkChanged()
        val next = h.engine.commands.last() as EngineCommand.Start
        assertTrue(next.token.sessionId.generation > install.token.sessionId.generation)
    }

    /** 手动断开撤销运行意图，后续网络恢复不能再次启动。 */
    @Test fun manualDisconnectPreventsNetworkRestart() = runTest {
        val h = Harness(this); h.connected(); h.coordinator.disconnect(); h.stopConfirmed()
        val starts = h.engine.commands.count { it is EngineCommand.Start }
        h.coordinator.networkChanged()
        assertEquals(starts, h.engine.commands.count { it is EngineCommand.Start })
        assertEquals(ConnectionPhase.Idle, h.state.phase)
    }

    /** TUN 参数变化先确认旧借用结束并关闭旧 PFD，再建立递增身份的新 TUN。 */
    @Test fun tunReplacementClosesOldBorrowBeforeCreatingNew() = runTest {
        val h = Harness(this); val installed = h.connected()
        h.query(address = "10.42.1.9", attachment = installed.attachmentId, lastOperation = installed.token)
        val remove = h.engine.commands.last() as EngineCommand.RemoveTun
        assertFalse(h.platform.descriptors.single().closed)
        h.engine.emit(EngineEvent.TunRemoved(remove.token, remove.attachmentId)); runCurrent()
        val replacement = h.engine.commands.last() as EngineCommand.InstallTun
        assertTrue(h.platform.descriptors.first().closed)
        assertEquals(2, replacement.attachmentId.sequence)
        assertEquals(listOf("establish", "pfd-close", "establish"), h.order.filter { it in setOf("establish", "pfd-close") })
        h.attach(); assertEquals("10.42.1.9", h.state.applied!!.virtualIpv4.value)
    }

    /** 应用配置校验失败保持旧运行与 PFD，保存修订不会被当作已应用。 */
    @Test fun invalidApplyKeepsPreviousRuntime() = runTest {
        val h = Harness(this); h.connected()
        h.engine.valid = ConfirmationResult.Failed(CoreFailure(CoreErrorCode.InvalidConfig))
        h.coordinator.apply(saved().copy(revision = 2, core = saved().core.copy(mtu = 1400))); runCurrent()
        assertEquals(ConnectionPhase.Running, h.state.phase)
        assertEquals(1, h.state.applied!!.saved.revision)
        assertEquals(OperationKind.Apply, h.state.failure!!.kind)
        assertFalse(h.platform.descriptors.single().closed)
        assertTrue(h.engine.commands.none { it is EngineCommand.Stop })
    }

    /** 平台建 TUN 尚未返回时停止可及时进入；停止排空后仍等待迟到 PFD 关闭再释放 JNI。 */
    @Test fun cancelWhileEstablishingWaitsForLateOwnedDescriptor() = runTest {
        val io = PausedDispatcher(); val h = Harness(this, io)
        val start = h.start(); h.coreStarted(start); h.query()
        assertTrue(h.platform.descriptors.isEmpty())
        h.coordinator.disconnect(); h.stopConfirmed()
        assertFalse(h.order.contains("jni-release"))
        io.drain(); runCurrent()
        assertTrue(h.platform.descriptors.single().closed)
        assertEquals(listOf("native-drained", "establish", "pfd-close", "jni-release", "foreground-end"), h.order)
    }

    /** 保护回调失败计数到达后统一停止，不能靠核心仍 Running 维持表面成功。 */
    @Test fun protectionFailureInitiatesUnifiedStop() = runTest {
        val h = Harness(this); val installed = h.connected()
        h.query(attachment = installed.attachmentId, lastOperation = installed.token, failures = 1)
        assertEquals(ConnectionPhase.Stopping, h.state.phase)
        h.stopConfirmed()
        assertEquals(ConnectionPhase.Failed, h.state.phase)
        assertEquals(UnavailableReason.ProtectionFailed, h.state.failure!!.reason)
    }

    /** 物理网络已失效但网络回调尚未处理时，保护失败先收束旧会话并保留恢复意图。 */
    @Test fun protectionFailureOnLostNetworkDoesNotEraseRunIntent() = runTest {
        val h = Harness(this); val installed = h.connected()
        h.platform.networkAvailable = false
        h.query(attachment = installed.attachmentId, lastOperation = installed.token, failures = 1)
        assertEquals(ConnectionPhase.Stopping, h.state.phase)
        h.stopConfirmed()
        assertEquals(ConnectionPhase.Reconnecting, h.state.phase)
        h.platform.networkAvailable = true; h.coordinator.networkChanged()
        val next = h.engine.commands.last() as EngineCommand.Start
        assertTrue(next.token.sessionId.generation > installed.token.sessionId.generation)
    }

    /** 核心替身直接发整体 Ready 无法越过平台准备与安装确认。 */
    @Test fun coreCannotForgeOverallReady() = runTest {
        val h = Harness(this); val start = h.start()
        h.engine.emit(EngineEvent.Ready(start.token, Observation("10.42.0.9", 0, DataFreshness.Fresh))); runCurrent()
        assertEquals(ConnectionPhase.Connecting, h.state.phase)
        assertNull(h.state.applied)
        assertTrue(h.platform.descriptors.isEmpty())
    }

    /** 未授权时拒绝连接，不注册保护器或提交核心启动。 */
    @Test fun permissionIsRequiredBeforeAnyCoreWork() = runTest {
        val h = Harness(this); h.platform.authorized = false
        assertEquals(OperationResult.Rejected(UnavailableReason.PermissionDenied), h.coordinator.connect(saved()))
        assertTrue(h.engine.commands.isEmpty())
        assertNull(h.platform.allowed)
        assertEquals(ConnectionPhase.Idle, h.state.phase)
    }

    /** 启动中撤销先禁用保护，再等待停止确认；迟到启动不能恢复运行意图。 */
    @Test fun revokeDuringStartWaitsForDrainAndRejectsLateStart() = runTest {
        val h = Harness(this); val start = h.start()
        h.coordinator.revoke()
        assertNull(h.platform.allowed)
        assertEquals(ConnectionPhase.Stopping, h.state.phase)
        h.coreStarted(start)
        assertEquals(2, h.engine.commands.count { it is EngineCommand.Stop })
        h.stopConfirmed()
        assertEquals(ConnectionPhase.Failed, h.state.phase)
        assertEquals(UnavailableReason.ServiceRevoked, h.state.failure!!.reason)
        h.coordinator.networkChanged()
        assertEquals(1, h.engine.commands.count { it is EngineCommand.Start })
        assertTrue(h.platform.descriptors.isEmpty())
    }

    /** 应用已保存修订必须先排空旧会话；新安装确认前旧运行快照只作为历史。 */
    @Test fun savedRevisionIsAppliedOnlyAfterNewTunConfirmation() = runTest {
        val h = Harness(this); val old = h.connected()
        val next = saved().copy(revision = 2, core = saved().core.copy(mtu = 1400))
        assertTrue(h.coordinator.apply(next) is OperationResult.Accepted); runCurrent()
        assertEquals(ConnectionPhase.Stopping, h.state.phase)
        assertEquals(1, h.state.applied!!.saved.revision)
        assertEquals(DataFreshness.Stale, h.state.applied!!.freshness)
        assertFalse(h.platform.descriptors.first().closed)
        h.stopConfirmed()
        val start = h.engine.commands.last() as EngineCommand.Start
        assertEquals(next, start.saved)
        assertTrue(start.token.sessionId.generation > old.token.sessionId.generation)
        assertTrue(h.platform.descriptors.first().closed)
        h.coreStarted(start); h.query()
        assertEquals(ConnectionPhase.PreparingTun, h.state.phase)
        assertEquals(1, h.state.applied!!.saved.revision)
        h.attach()
        assertEquals(2, h.state.applied!!.saved.revision)
        assertEquals(DataFreshness.Fresh, h.state.applied!!.freshness)
    }

    /** 新修订启动失败如实停止；显式恢复使用上次确认的修订，不改传入的新保存内容。 */
    @Test fun failedApplyRestoresLastConfirmedRevisionExplicitly() = runTest {
        val h = Harness(this); h.connected()
        val next = saved().copy(revision = 2, core = saved().core.copy(mtu = 1400))
        h.coordinator.apply(next); runCurrent(); h.stopConfirmed()
        val failed = h.engine.commands.last() as EngineCommand.Start
        h.engine.emit(EngineEvent.Failed(failed.token,
            UnavailableReason.EngineRejected, FailureDisposition.Stopped)); runCurrent()
        h.stopConfirmed()
        assertEquals(ConnectionPhase.Failed, h.state.phase)
        assertEquals(OperationKind.Apply, h.state.failure!!.kind)
        assertEquals(1, h.state.applied!!.saved.revision)
        assertEquals(DataFreshness.Stale, h.state.applied!!.freshness)
        assertTrue(h.coordinator.restoreRunning() is OperationResult.Accepted); runCurrent()
        val restored = h.engine.commands.last() as EngineCommand.Start
        assertEquals(saved(), restored.saved)
        h.coreStarted(restored); h.query(); h.attach()
        assertEquals(1, h.state.applied!!.saved.revision)
        assertEquals(2, next.revision)
    }

    /** 停止确认后 JNI 释放仍未确认时保留会话身份，禁止重连，并保留重复停止入口。 */
    @Test fun unknownJniReleaseBlocksRestartUntilReleaseConfirmed() = runTest {
        val h = Harness(this); h.connected()
        h.engine.release = ConfirmationResult.Unknown(CoreFailure(CoreErrorCode.ConfirmationTimedOut))
        h.coordinator.disconnect(); h.stopConfirmed()
        assertTrue(h.platform.descriptors.single().closed)
        assertEquals(ConnectionPhase.OutcomeUnknown, h.state.phase)
        assertEquals(1, h.coordinator.activeSessionCount)
        assertEquals(0, h.coordinator.ownedTunCount)
        assertEquals(OperationResult.Rejected(UnavailableReason.Busy), h.coordinator.connect(saved()))
        h.engine.release = ConfirmationResult.Confirmed(Unit)
        assertTrue(h.coordinator.disconnect() is OperationResult.Accepted); h.stopConfirmed()
        assertEquals(ConnectionPhase.Idle, h.state.phase)
        assertEquals(0, h.coordinator.activeSessionCount)
    }

    /** 新代次运行后旧会话的地址、失败和安装回执均不能影响新运行事实。 */
    @Test fun oldSessionReceiptsCannotAlterReplacementSession() = runTest {
        val h = Harness(this); val old = h.connected()
        h.coordinator.disconnect(); h.stopConfirmed()
        val next = h.connected(); val before = h.state
        assertNotEquals(old.token.sessionId, next.token.sessionId)
        h.engine.emit(EngineEvent.TunAttached(old.token, TunInstalled(old.attachmentId, old.spec)))
        h.engine.emit(EngineEvent.Failed(old.token,
            UnavailableReason.EngineRejected, FailureDisposition.Stopped)); runCurrent()
        assertEquals(before, h.state)
        assertFalse(h.platform.descriptors.last().closed)
    }

    /** 启动中的系统撤销和迟到启动按固定时间重放，最终失败且不恢复运行意图。 */
    @Test fun replayRevocationDuringStartup() = runTest { replay("startup-revoke") }

    /** 停止后重连收到旧安装与错误回执，重放全过程中新会话保持 Running。 */
    @Test fun replayLateReceiptsAfterStop() = runTest { replay("late-after-stop") }

    /** 应用安装回执丢失及未知查询保持历史配置；显式停止确认后恢复旧修订。 */
    @Test fun replayLostApplyReceipt() = runTest { replay("lost-apply-receipt") }

    /** 版本化脱敏夹具仅含相对时间、事件分类与行为预期，不携带配置秘密或原始日志。 */
    private fun TestScope.replay(name: String) {
        val lines = requireNotNull(javaClass.getResourceAsStream("/fault-replay/$name.events"))
            .bufferedReader().use { it.readLines() }
        require(lines.first() == "p02-events-v1")
        val h = Harness(this)
        var oldInstall: EngineCommand.InstallTun? = null
        for (line in lines.drop(1).filter { it.isNotBlank() && !it.startsWith("#") }) {
            val columns = line.split(' ')
            require(columns.size == 6) { "事件字段数不符：$name" }
            val (time, action, phase, revision, freshness) = columns
            val owned = columns[5]
            val elapsed = time.toLong() - testScheduler.currentTime
            require(elapsed >= 0) { "事件时间倒退：$name" }
            advanceTimeBy(elapsed); runCurrent()
            when (action) {
                "start" -> h.start()
                "connected" -> oldInstall = h.connected()
                "started" -> h.coreStarted(h.engine.commands.last { it is EngineCommand.Start } as EngineCommand.Start)
                "address" -> h.query()
                "installed" -> h.attach()
                "revoke" -> h.coordinator.revoke()
                "stop" -> h.coordinator.disconnect()
                "stopped" -> h.stopConfirmed()
                "network" -> h.coordinator.networkChanged()
                "restore" -> { assertTrue(h.coordinator.restoreRunning() is OperationResult.Accepted); runCurrent() }
                "apply" -> {
                    assertTrue(h.coordinator.apply(saved().copy(revision = 2, core = saved().core.copy(mtu = 1400))) is OperationResult.Accepted)
                    runCurrent()
                }
                "late-error" -> {
                    h.engine.emit(EngineEvent.Failed(oldInstall!!.token, UnavailableReason.EngineRejected, FailureDisposition.Stopped)); runCurrent()
                }
                "late-install" -> {
                    h.engine.emit(EngineEvent.TunAttached(oldInstall!!.token, TunInstalled(oldInstall!!.attachmentId, oldInstall!!.spec))); runCurrent()
                }
                "tick" -> Unit
                "query-unknown", "query-running" -> {
                    val install = h.engine.commands.last { it is EngineCommand.InstallTun } as EngineCommand.InstallTun
                    h.query(attachment = install.attachmentId, lastOperation = install.token,
                        status = if (action == "query-unknown") RuntimeStatus.Unknown else RuntimeStatus.Running)
                    if (action == "query-unknown") {
                        assertEquals(OperationResult.Rejected(UnavailableReason.Busy), h.coordinator.apply(saved().copy(revision = 3)))
                        assertFalse(h.platform.descriptors.last().closed)
                    }
                }
                else -> error("未知事件：$action")
            }
            runCurrent()
            assertEquals("$name: $line", ConnectionPhase.valueOf(phase), h.state.phase)
            assertEquals("$name: $line", revision.toLongOrNull(), h.state.applied?.saved?.revision)
            assertEquals("$name: $line", freshness.takeUnless { it == "-" }?.let(DataFreshness::valueOf), h.state.applied?.freshness)
            assertEquals("$name: $line", owned.toInt(), h.coordinator.ownedTunCount)
        }
        assertEquals(0, h.coordinator.activeSessionCount)
        assertTrue(h.platform.descriptors.all { it.closed })
    }

    private fun saved() = SavedRevision(LocalProfileId("00000000-0000-4000-8000-000000000002"), 1,
        CoreConfig(CoreInstanceId("synthetic-core"), "synthetic-network", null, Ipv4Assignment.Static("10.222.0.2"),
            networkSecret = NetworkSecret.of("synthetic-public-test-only"), peers = listOf(PeerEndpoint("tcp://127.0.0.1:11010"))), AndroidVpnPolicy())
}
