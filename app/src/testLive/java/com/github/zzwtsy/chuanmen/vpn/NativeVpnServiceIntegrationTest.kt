package com.github.zzwtsy.chuanmen.vpn

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.VpnService
import android.os.Process
import com.github.zzwtsy.chuanmen.application.LiveApplication
import com.github.zzwtsy.chuanmen.contracts.*
import com.github.zzwtsy.chuanmen.feature.configuration.storage.MemoryProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowVpnService

/** 保留正式 Service 和协调器，只替换 CoreEngine；Android 平台由 Robolectric 提供。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], application = LiveApplication::class)
@OptIn(ExperimentalCoroutinesApi::class)
class NativeVpnServiceIntegrationTest {
    class RecordingCore : CoreEngine {
        override val capabilities = setOf(Capability.Connect)
        val commands = mutableListOf<EngineCommand>()
        val receipts = Channel<EngineEvent>(Channel.UNLIMITED)
        override val events = receipts.receiveAsFlow()
        var releases = 0
        override fun submit(command: EngineCommand) { commands += command }
        override suspend fun releaseSession(token: OperationToken): ConfirmationResult<Unit> { releases++; return ConfirmationResult.Confirmed(Unit) }
    }
    class ServiceWithCore : NativeVpnService() {
        override fun createEngine(scope: CoroutineScope): CoreEngine = core
        companion object { lateinit var core: RecordingCore }
    }

    private fun setup(): Pair<org.robolectric.android.controller.ServiceController<ServiceWithCore>, NativeVpnService.ControlBinder> {
        val app = RuntimeEnvironment.getApplication() as LiveApplication
        app.setRepository(MemoryProfileRepository(listOf(ProfileSummary(saved(), "本地名称"))))
        ShadowBinder.setCallingUid(Process.myUid())
        ShadowVpnService.setPrepareResult(null)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        val shadow = shadowOf(connectivity)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        val network = connectivity.activeNetwork!!
        val capabilities = NetworkCapabilities()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        shadow.setNetworkCapabilities(network, capabilities)
        ServiceWithCore.core = RecordingCore()
        val controller = Robolectric.buildService(ServiceWithCore::class.java).create()
        val binder = controller.get().onBind(Intent().setAction("com.github.zzwtsy.chuanmen.vpn.CONTROL")) as NativeVpnService.ControlBinder
        return controller to binder
    }

    /** 应用 Binder 每个入口核对 UID，其他 UID 无法读取状态或启动/停止会话。 */
    @Test fun binderRejectsOtherUid() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (controller, binder) = setup()
        try {
            ShadowBinder.setCallingUid(Process.myUid() + 1)
            assertThrows(SecurityException::class.java) { binder.state() }
            assertThrows(SecurityException::class.java) { binder.connect(saved()) }
            assertThrows(SecurityException::class.java) { binder.disconnect() }
        } finally { ShadowBinder.setCallingUid(Process.myUid()); controller.destroy(); runCurrent(); Dispatchers.resetMain() }
    }

    /** 未保存修订和未授权请求均在核心前拒绝；仍使用正式 Service 的入口校验。 */
    @Test fun unsavedRevisionAndMissingAuthorizationNeverReachCore() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (controller, binder) = setup()
        try {
            assertEquals(OperationResult.Rejected(UnavailableReason.InvalidConfig), binder.connect(saved().copy(revision = 2)))
            ShadowVpnService.setPrepareResult(Intent("synthetic-permission"))
            assertEquals(OperationResult.Rejected(UnavailableReason.PermissionDenied), binder.connect(saved()))
            assertTrue(ServiceWithCore.core.commands.isEmpty())
        } finally { controller.destroy(); runCurrent(); Dispatchers.resetMain() }
    }

    /** 已授权启动注册正式保护回调；撤销立即拒绝旧回调，停止回执后才释放核心引用。 */
    @Test fun revokeUsesFormalStopAndReleaseWiring() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val (controller, binder) = setup()
        try {
            assertTrue(binder.connect(saved()) is OperationResult.Accepted)
            runCurrent()
            val start = ServiceWithCore.core.commands.last() as EngineCommand.Start
            assertNotNull(start.protector)
            controller.get().onRevoke(); runCurrent()
            assertFalse(start.protector!!.protect(7, start.token.sessionId))
            val stop = ServiceWithCore.core.commands.last() as EngineCommand.Stop
            assertEquals(0, ServiceWithCore.core.releases)
            ServiceWithCore.core.receipts.send(EngineEvent.Stopped(stop.token, StopConfirmation(stop.token.sessionId, true)))
            runCurrent()
            assertEquals(1, ServiceWithCore.core.releases)
            assertEquals(ConnectionPhase.Failed, binder.state().value.phase)
            assertEquals(UnavailableReason.ServiceRevoked, binder.state().value.failure!!.reason)
            assertTrue(shadowOf(controller.get()).isForegroundStopped)
        } finally { controller.destroy(); runCurrent(); Dispatchers.resetMain() }
    }

    private fun saved() = SavedRevision(LocalProfileId("00000000-0000-4000-8000-000000000002"), 1,
        CoreConfig(CoreInstanceId("synthetic-core"), "synthetic-network", null, Ipv4Assignment.Static("10.222.0.2"),
            networkSecret = NetworkSecret.of("synthetic-public-test-only"), peers = listOf(PeerEndpoint("tcp://127.0.0.1:11010"))), AndroidVpnPolicy())
}
