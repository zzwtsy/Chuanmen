package com.github.zzwtsy.chuanmen.application

import android.graphics.Bitmap
import androidx.core.view.drawToBitmap
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 固定 API 37 的 JVM Android 模拟与 native graphics 渲染，不能代替设备、TalkBack 或系统栏验收。 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DemoPresentationJvmTest(case: PresentationCase) : DemoPresentationContract(case) {
    override fun captureDirectory() = File("build/p01-ui")

    /** JVM 没有真实显示 Surface，直接用 native Canvas 绘制窗口；API 26+ 设备通过 PixelCopy 捕获。 */
    override fun captureBitmap(): Bitmap {
        lateinit var bitmap: Bitmap
        compose.runOnIdle { bitmap = compose.activity.window.decorView.drawToBitmap() }
        return bitmap
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases() = DemoPresentationContract.cases()
    }
}
