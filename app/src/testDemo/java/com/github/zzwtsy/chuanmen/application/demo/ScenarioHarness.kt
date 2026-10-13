package com.github.zzwtsy.chuanmen.application.demo

import com.github.zzwtsy.chuanmen.contracts.MonotonicClock
import com.github.zzwtsy.chuanmen.feature.configuration.edit.ConfigurationEditor
import com.github.zzwtsy.chuanmen.feature.configuration.storage.DemoProfileRepository
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.ExperimentalCoroutinesApi

/** 替身和控制器共用测试调度器；后台事件收集在 runTest 结束时自动取消。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScenarioHarness(scope: TestScope, scenario: DemoScenario, timeoutMillis: Long = 5000) {
    val repository = DemoProfileRepository(scenario.profiles)
    val editor = ConfigurationEditor(repository)
    val engine = FakeEngine(
        MonotonicClock { scope.testScheduler.currentTime }, ScheduledEngineEvents(scope.backgroundScope), scenario.script,
    )
    val sessions = DemoSessionController(engine, scope.backgroundScope, scenario.initialSession, timeoutMillis)
}
