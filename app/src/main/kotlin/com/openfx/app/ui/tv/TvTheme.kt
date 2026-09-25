package com.openfx.app.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@OptIn(ExperimentalTvMaterial3Api::class)
private val TvDarkColorScheme = darkColorScheme(
    primary = Color(0xFF57E3A1),
    onPrimary = Color(0xFF062B1C),
    secondary = Color(0xFFFFC857),
    onSecondary = Color(0xFF332100),
    background = Color(0xFF101416),
    onBackground = Color(0xFFF4F7F5),
    surface = Color(0xFF171D20),
    onSurface = Color(0xFFF4F7F5),
    surfaceVariant = Color(0xFF252D31),
    onSurfaceVariant = Color(0xFFD5DEE0),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3A0505),
    border = Color(0xFF667378)
)

/**
 * TV 端主題 — 暗色背景，大字體，適合遙控器操作
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun OpenFXTvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = TvDarkColorScheme,
        content = content
    )
}
