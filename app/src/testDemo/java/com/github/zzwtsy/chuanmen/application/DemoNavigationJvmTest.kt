package com.github.zzwtsy.chuanmen.application

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 在 JVM 模拟 API 37 运行实际 demo Activity，验证绑定与交互，结果不冒充设备测试。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoNavigationJvmTest : DemoNavigationContract()
