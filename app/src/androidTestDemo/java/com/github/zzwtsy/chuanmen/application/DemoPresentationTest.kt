package com.github.zzwtsy.chuanmen.application

import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 在实际设备/模拟器执行共享渲染契约，结果与 JVM 模拟环境分别登记。 */
@RunWith(Parameterized::class)
class DemoPresentationTest(case: PresentationCase) : DemoPresentationContract(case) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases() = DemoPresentationContract.cases()
    }
}
