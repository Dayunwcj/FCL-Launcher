package com.doubao.mclauncher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.doubao.mclauncher.ui.AppRoot
import com.doubao.mclauncher.ui.theme.MCTheme

/**
 * 唯一 Activity，所有界面都交给 Compose。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MCTheme {
                AppRoot()
            }
        }
    }
}
