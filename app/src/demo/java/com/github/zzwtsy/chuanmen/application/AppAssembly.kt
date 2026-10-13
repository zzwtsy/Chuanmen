package com.github.zzwtsy.chuanmen.application

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.github.zzwtsy.chuanmen.application.demo.DemoFixtures
import com.github.zzwtsy.chuanmen.application.demo.DemoSessionController
import com.github.zzwtsy.chuanmen.application.demo.FakeEngine
import com.github.zzwtsy.chuanmen.application.demo.ScheduledEngineEvents
import com.github.zzwtsy.chuanmen.contracts.MonotonicClock
import com.github.zzwtsy.chuanmen.feature.configuration.storage.DemoProfileRepository

/** 只有 demo flavor 可以引用模拟实现，Activity 和主源码不选择 fake。 */
object AppAssembly {
    val factory = object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == ChuanmenViewModel::class.java)
            val scenario = DemoFixtures.connected
            val model = ChuanmenViewModel { scope ->
                // 演示时间固定；JVM 测试另注入虚拟调度器时钟，不使用真实计时等待。
                val engine = FakeEngine(MonotonicClock { DemoFixtures.fixtureTimeMillis }, ScheduledEngineEvents(scope), scenario.script)
                AppDependencies(
                    DemoProfileRepository(scenario.profiles), DemoSessionController(engine, scope, scenario.initialSession),
                    AppReadings(scenario.readings.txBytesPerSecond, scenario.readings.rxBytesPerSecond, scenario.readings.logs),
                )
            }
            @Suppress("UNCHECKED_CAST") // factory 已核对 modelClass，返回约定的 ViewModel 类型。
            return model as T
        }
    }
}
