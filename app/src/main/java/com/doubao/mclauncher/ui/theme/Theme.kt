package com.doubao.mclauncher.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 草方块配色，简单粗暴
private val Grass = Color(0xFF5B8C3E)
private val Dirt = Color(0xFF7B5B3A)
private val DarkBg = Color(0xFF121212)
private val CardBg = Color(0xFF1E1E1E)

private val DarkColors = darkColorScheme(
    primary = Grass,
    onPrimary = Color.White,
    secondary = Dirt,
    background = DarkBg,
    surface = CardBg,
    onBackground = Color(0xFFEDEDED),
    onSurface = Color(0xFFEDEDED)
)

private val LightColors = lightColorScheme(
    primary = Grass,
    onPrimary = Color.White,
    secondary = Dirt
)

@Composable
fun MCTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
