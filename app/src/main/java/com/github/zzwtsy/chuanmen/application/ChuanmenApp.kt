package com.github.zzwtsy.chuanmen.application

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.github.zzwtsy.chuanmen.shared.designsystem.theme.ChuanmenTheme

/** 应用组合入口；当前保留模板内容，导航与业务切片在 P01 实施。 */
@Composable
fun ChuanmenApp() {
    ChuanmenTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            Greeting(name = "Android", modifier = Modifier.padding(innerPadding))
        }
    }
}

@Composable
private fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(text = "Hello $name!", modifier = modifier)
}

@Preview(showBackground = true)
@Composable
private fun GreetingPreview() {
    ChuanmenTheme {
        Greeting("Android")
    }
}
