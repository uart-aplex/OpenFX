package com.openfx.common.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openfx.common.util.AppLocale
import com.openfx.mobile.R

/** 語言選單 (手機設定頁、TV 首頁共用)；用 TextButton 讓遙控器 D-pad 可以移動 */
@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current = remember { AppLocale.current(context) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_language)) },
        text = {
            Column {
                AppLocale.SUPPORTED.forEach { tag ->
                    val selected = tag == current
                    TextButton(
                        onClick = {
                            onDismiss()
                            context.findActivity()?.let { AppLocale.set(it, tag) }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (selected) Modifier.focusRequester(focus) else Modifier)
                    ) {
                        if (selected) Icon(Icons.Default.Check, contentDescription = null)
                        Text(AppLocale.displayName(tag) ?: stringResource(R.string.settings_language_system))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun languageLabel(): String {
    val context = LocalContext.current
    return AppLocale.displayName(AppLocale.current(context)) ?: stringResource(R.string.settings_language_system)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
