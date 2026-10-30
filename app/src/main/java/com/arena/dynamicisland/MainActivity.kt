package com.arena.dynamicisland

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.arena.dynamicisland.demo.DemoApp
import com.arena.dynamicisland.ui.theme.DynamicIslandTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // The island is meant to hang off the top edge, over the real status bar.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            DynamicIslandTheme {
                DemoApp()
            }
        }
    }
}
