package com.openfx.app.ui.tv

import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@OptIn(ExperimentalTvMaterial3Api::class)
private val TvDarkColorScheme = darkColorScheme()

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
