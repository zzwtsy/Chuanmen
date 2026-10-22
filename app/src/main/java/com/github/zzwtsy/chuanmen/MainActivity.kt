package com.github.zzwtsy.chuanmen

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppAssembly.attach(this)
        enableEdgeToEdge()
        setContent {
            AppAssembly.Content()
        }
    }

    override fun onDestroy() {
        AppAssembly.detach(this)
        super.onDestroy()
    }
}
