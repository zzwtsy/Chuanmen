package com.github.zzwtsy.chuanmen.features.configuration

import androidx.lifecycle.ViewModelStore
import com.github.zzwtsy.chuanmen.features.configuration.lib.*
import com.github.zzwtsy.chuanmen.lib.profile.*
import com.github.zzwtsy.chuanmen.lib.vpn.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*

/** 功能回归只调用生产 ViewModel；测试装配不复制保存流程。 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ConfigurationHarness(val scope: TestScope, val repository: ProfileRepository, val sessions: RecordingSessions, codec: CoreConfigCodec) {
    val viewed = MutableStateFlow(repository.profiles.value.firstOrNull()?.saved?.profileId)
    val model = ConfigurationViewModel(repository, sessions, viewed, codec, StandardTestDispatcher(scope.testScheduler))
    val store = ViewModelStore().also { it.put("configuration", model) }
    val draft get() = requireNotNull(model.state.value.draft)
    fun act(action: ConfigurationAction) { model.act(action); scope.runCurrent() }
    fun edit(field: DraftField, raw: String) = act(ConfigurationAction.Edit(field, raw))
    fun view(id: LocalProfileId) { viewed.value = id; scope.runCurrent() }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.withConfiguration(repository: ProfileRepository, sessions: RecordingSessions = RecordingSessions(SessionState()), codec: CoreConfigCodec = TestCoreConfigCodec(), block: suspend (ConfigurationHarness) -> Unit) {
    Dispatchers.setMain(StandardTestDispatcher(testScheduler))
    val harness = ConfigurationHarness(this, repository, sessions, codec)
    try {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { harness.model.state.collect {} }
        runCurrent()
        block(harness)
    } finally { harness.store.clear(); runCurrent(); Dispatchers.resetMain() }
}
