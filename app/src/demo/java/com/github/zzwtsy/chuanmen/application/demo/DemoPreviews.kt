package com.github.zzwtsy.chuanmen.application.demo

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.github.zzwtsy.chuanmen.application.AppNavigation
import com.github.zzwtsy.chuanmen.application.AppReadings
import com.github.zzwtsy.chuanmen.application.AppUiState
import com.github.zzwtsy.chuanmen.application.ChuanmenScreen
import com.github.zzwtsy.chuanmen.application.Destination
import com.github.zzwtsy.chuanmen.feature.configuration.edit.Draft
import com.github.zzwtsy.chuanmen.shared.designsystem.theme.ChuanmenTheme

/** Preview 只转换 fixture 快照，不启动调度器、引擎或网络。 */
fun DemoScenario.uiState(destination: Destination = Destination.Overview): AppUiState {
    val saved = profiles.firstOrNull()?.saved
    return AppUiState(
        profiles, AppNavigation(destination, saved?.profileId), saved?.let { Draft(it) }, initialSession,
        AppReadings(readings.txBytesPerSecond, readings.rxBytesPerSecond, readings.logs, simulated = true),
    )
}

class DemoScenarioProvider : PreviewParameterProvider<DemoScenario> {
    override val values = DemoFixtures.all.asSequence()
}

@Preview(name = "浅色 · 动态色关闭", showBackground = true)
@Preview(name = "深色", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "大字体", showBackground = true, fontScale = 1.6f)
@Composable
private fun DemoPreview(@PreviewParameter(DemoScenarioProvider::class) scenario: DemoScenario) {
    ChuanmenTheme(dynamicColor = false) { ChuanmenScreen(scenario.uiState()) {} }
}

@Preview(name = "高对比 · 配置", showBackground = true)
@Composable
private fun HighContrastPreview() {
    ChuanmenTheme(dynamicColor = false, highContrast = true) {
        ChuanmenScreen(DemoFixtures.connected.uiState(Destination.Configuration)) {}
    }
}
