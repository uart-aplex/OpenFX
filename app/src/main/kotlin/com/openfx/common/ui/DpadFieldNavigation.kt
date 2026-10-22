package com.openfx.common.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager

/**
 * 讓遙控器 D-pad 可以離開單行輸入框
 *
 * Compose 的 TextField 會吃掉上/下方向鍵 (拿來移動游標)，在 TV 上焦點會卡在輸入框裡出不來。
 * 這裡攔截上/下鍵改成移動焦點；左/右鍵仍保留給游標移動。
 */
@Composable
fun Modifier.dpadFieldNavigation(): Modifier {
    val focusManager = LocalFocusManager.current
    return onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
            Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}

/** 鍵盤上的「下一個」(imeAction = Next) 移到下一個欄位 */
@Composable
fun nextFieldKeyboardActions(): KeyboardActions {
    val focusManager = LocalFocusManager.current
    return KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) })
}
