package com.github.zzwtsy.chuanmen.lib.vpn

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.zzwtsy.chuanmen.lib.profile.AndroidVpnPolicy
import com.github.zzwtsy.chuanmen.lib.profile.CoreConfig
import com.github.zzwtsy.chuanmen.lib.profile.CoreInstanceId
import com.github.zzwtsy.chuanmen.lib.profile.Ipv4Assignment
import com.github.zzwtsy.chuanmen.lib.profile.LocalProfileId
import com.github.zzwtsy.chuanmen.lib.profile.NetworkSecret
import com.github.zzwtsy.chuanmen.lib.profile.PeerEndpoint
import com.github.zzwtsy.chuanmen.lib.profile.SavedRevision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 加载正式 APK 的实际 JNI 库；pipe 只验证借用协议，不是平台 VPN 或真实数据通路。 */
@RunWith(AndroidJUnit4::class)
class NativeBridgeContractTest {
    /** 设备上的版本查询来自实际库，核对固定 commit、协议、ABI 与 Android 平台。 */
    @Test fun loadedLibraryHasFormalBuildIdentity() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val info = NativeCoreEngine(scope).buildInfo()
            assertTrue(info is ConfirmationResult.Confirmed)
            val build = (info as ConfirmationResult.Confirmed<CoreBuildInfo>).value
            assertEquals("7592951b808c4839cb9d77cc4bc642f124915951", build.coreRevision)
            assertEquals("android", build.platform)
            assertEquals(BridgeProtocol.VERSION, build.protocol)
            assertTrue(build.abi in setOf("arm64-v8a", "x86_64"))
        } finally { scope.cancel() }
    }

    /** 真实 JNI 安装 pipe 借用并计成功读；停止后 Kotlin 关闭 fd，再独立释放 JNI 引用。 */
    @Test fun borrowedPipeIsDrainedBeforePfdAndJniRelease() = runBlocking {
        val lease = Lease()
        retained += lease
        val saved = saved()
        val session = SessionId("synthetic-native-pipe", System.nanoTime().coerceAtLeast(1))
        var sequence = 0L
        fun token() = OperationToken(session, ++sequence)
        try {
            val start = token()
            lease.engine.submit(EngineCommand.Start(start, saved, SocketProtector { _, _ -> false }))
            assertTrue(lease.await(start) is EngineEvent.CoreStarted)
            val attachment = AttachmentId(session, 1)
            val install = token()
            val spec = VpnInterfaceSpec(listOf(Ipv4Cidr("10.222.0.2", 24)), listOf(Ipv4Cidr("10.222.0.0", 24)), emptyList(), 1380)
            lease.engine.submit(EngineCommand.InstallTun(install, attachment, lease.pipe[0].fd, spec))
            assertTrue(lease.await(install) is EngineEvent.TunAttached)
            ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(lease.pipe[1].fileDescriptor)).use {
                it.write(ByteArray(20).apply { this[0] = 0x45; this[3] = 20 })
            }
            val snapshot = withTimeout(5_000) {
                var actual: CoreSnapshot
                do {
                    delay(50)
                    val query = token(); lease.engine.submit(EngineCommand.QueryRuntime(query))
                    actual = (lease.await(query) as EngineEvent.CoreObserved).snapshot
                } while ((actual.counters?.txBytes ?: 0) == 0L)
                actual
            }
            assertTrue(snapshot.counters!!.txBytes >= 20)
            assertEquals(attachment, snapshot.attachmentId)
            val stop = token(); lease.engine.submit(EngineCommand.Stop(stop))
            assertTrue(lease.await(stop) is EngineEvent.Stopped)
            lease.drained = true
            val queryStopped = token(); lease.engine.submit(EngineCommand.QueryRuntime(queryStopped))
            val stopped = (lease.await(queryStopped) as EngineEvent.CoreObserved).snapshot
            assertEquals(0L, stopped.activeInstances)
            assertNull(stopped.attachmentId)
            assertEquals(1L, stopped.jniGlobalRefs)
            lease.closeDescriptors()
            assertEquals(ConfirmationResult.Confirmed(Unit), lease.engine.releaseSession(token()))
            lease.released = true
            lease.scope.cancel()
            retained -= lease
        } finally {
            if (!lease.released) {
                val stop = token(); lease.engine.submit(EngineCommand.Stop(stop))
                if (lease.await(stop) is EngineEvent.Stopped) {
                    lease.drained = true
                    lease.closeDescriptors()
                    if (lease.engine.releaseSession(token()) is ConfirmationResult.Confirmed) {
                        lease.released = true; lease.scope.cancel(); retained -= lease
                    }
                }
            }
        }
    }

    /** 拒绝或抛异常的保护器均不能放行；失败安装可收束，替换隔离旧身份，并发停止分别确认。 */
    @Test fun failedProtectionInstallReplacementAndConcurrentStopAreConfirmed() = runBlocking {
        for (throws in listOf(false, true)) {
            val lease = Lease(); retained += lease
            val session = SessionId("synthetic-native-negative", System.nanoTime().coerceAtLeast(1))
            var sequence = 0L
            fun token() = OperationToken(session, ++sequence)
            suspend fun snapshot(): CoreSnapshot {
                val query = token(); lease.engine.submit(EngineCommand.QueryRuntime(query))
                return (lease.await(query) as EngineEvent.CoreObserved).snapshot
            }
            try {
                val start = token()
                lease.engine.submit(EngineCommand.Start(start, saved(), SocketProtector { _, _ ->
                    if (throws) throw IllegalStateException("synthetic protection failure") else false
                }))
                assertTrue(lease.await(start) is EngineEvent.CoreStarted)
                val denied = withTimeout(5_000) {
                    var actual: CoreSnapshot
                    do { delay(50); actual = snapshot() } while (actual.protectionFailures == 0L)
                    actual
                }
                assertTrue(denied.protectionCalls > 0)
                assertTrue(denied.peers.none { it.connections > 0 })
                val spec = VpnInterfaceSpec(listOf(Ipv4Cidr("10.222.0.2", 24)), listOf(Ipv4Cidr("10.222.0.0", 24)), emptyList(), 1380)
                val failedInstall = token()
                lease.engine.submit(EngineCommand.InstallTun(failedInstall, AttachmentId(session, 1), Int.MAX_VALUE, spec))
                assertTrue(lease.await(failedInstall) is EngineEvent.Failed)
                assertEquals(TunBorrowState.Failed, snapshot().attachmentState)
                val old = AttachmentId(session, 2)
                val install = token(); lease.engine.submit(EngineCommand.InstallTun(install, old, lease.pipe[0].fd, spec))
                assertTrue(lease.await(install) is EngineEvent.TunAttached)
                val remove = token(); lease.engine.submit(EngineCommand.RemoveTun(remove, old))
                assertTrue(lease.await(remove) is EngineEvent.TunRemoved)
                assertNull(snapshot().attachmentId)
                assertTrue(lease.pipe[0].fd >= 0)
                val replacement = AttachmentId(session, 3)
                val next = token(); lease.engine.submit(EngineCommand.InstallTun(next, replacement, lease.pipe[0].fd, spec))
                assertTrue(lease.await(next) is EngineEvent.TunAttached)
                val stale = token(); lease.engine.submit(EngineCommand.RemoveTun(stale, old))
                assertTrue(lease.await(stale) is EngineEvent.Failed)
                assertEquals(replacement, snapshot().attachmentId)
                val stops = listOf(token(), token())
                val replies = async(start = CoroutineStart.UNDISPATCHED) {
                    withTimeout(15_000) { lease.engine.events.filter { it.token in stops }.take(2).toList() }
                }
                stops.forEach { lease.engine.submit(EngineCommand.Stop(it)) }
                val actualStops = replies.await()
                assertEquals(stops.toSet(), actualStops.map { it.token }.toSet())
                assertTrue(actualStops.all { it is EngineEvent.Stopped })
                lease.drained = true
                val stopped = snapshot()
                assertEquals(RuntimeStatus.Stopped, stopped.status)
                assertEquals(0L, stopped.activeInstances)
                assertEquals(0L, stopped.callbacksInFlight)
                assertNull(stopped.attachmentId)
                lease.closeDescriptors()
                assertEquals(ConfirmationResult.Confirmed(Unit), lease.engine.releaseSession(token()))
                lease.released = true; lease.scope.cancel(); retained -= lease
            } finally {
                if (!lease.released) {
                    val stop = token(); lease.engine.submit(EngineCommand.Stop(stop))
                    if (lease.await(stop) is EngineEvent.Stopped) {
                        lease.drained = true; lease.closeDescriptors()
                        if (lease.engine.releaseSession(token()) is ConfirmationResult.Confirmed) {
                            lease.released = true; lease.scope.cancel(); retained -= lease
                        }
                    }
                }
            }
        }
    }

    private class Lease {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val engine = NativeCoreEngine(scope)
        val pipe = ParcelFileDescriptor.createPipe()
        var drained = false
        var released = false
        var closed = false
        suspend fun await(token: OperationToken): EngineEvent = withTimeout(15_000) { engine.events.first { it.token == token } }
        fun closeDescriptors() { check(drained); if (!closed) { pipe.forEach { it.close() }; closed = true } }
    }
    companion object {
        // 未确认借用终止时保留强引用；测试失败不能借助 GC 提前关闭 native 仍可能使用的 fd。
        private val retained = mutableListOf<Lease>()
    }
    private suspend fun saved() = SavedRevision(LocalProfileId("00000000-0000-4000-8000-000000000002"), 1, CoreConfig(CoreInstanceId("synthetic-native-core"), "synthetic-native-network", null, Ipv4Assignment.Static("10.222.0.2"),
            networkSecret = NetworkSecret.of("synthetic-public-test-only"), peers = listOf(PeerEndpoint("tcp://127.0.0.1:9"))).controlledDocument(), AndroidVpnPolicy())
}
