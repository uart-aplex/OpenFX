package com.openfx.mobile.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openfx.core.sync.http.OpenFxHttpSender
import com.openfx.mobile.R

private const val PIN_LENGTH = 6

/**
 * 傳送時的配對碼輸入框 — 手機與 TV 共用，放在 Activity 最外層
 *
 * TransferManager 在送往尚未配對 (或配對碼已失效) 的接收端前會發出 pinRequest，
 * 這裡顯示對話框；驗證成功後 TransferManager 自動繼續傳送並記住配對碼。
 */
@Composable
fun PairingPinPrompt(viewModel: TransferViewModel) {
    val request by viewModel.pinRequest.collectAsState()
    val current = request ?: return

    var pin by remember(current.device.peerKey) { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val canSubmit = pin.length == PIN_LENGTH && !current.verifying

    LaunchedEffect(current.device.peerKey) {
        runCatching { focusRequester.requestFocus() }
    }
    // 輸錯後清空，方便遙控器重新輸入
    LaunchedEffect(current.error) {
        if (current.error != null) pin = ""
    }

    AlertDialog(
        onDismissRequest = viewModel::cancelPinRequest,
        title = { Text(stringResource(R.string.pairing_pin_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.pairing_pin_message, current.device.deviceName))
                OutlinedTextField(
                    value = pin,
                    onValueChange = { value -> pin = value.filter(Char::isDigit).take(PIN_LENGTH) },
                    label = { Text(stringResource(R.string.pairing_pin_hint)) },
                    singleLine = true,
                    enabled = !current.verifying,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        if (canSubmit) viewModel.submitPin(pin)
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                if (current.verifying) {
                    CircularProgressIndicator()
                } else {
                    current.error?.let { error ->
                        Text(
                            stringResource(error.messageRes()),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canSubmit, onClick = { viewModel.submitPin(pin) }) {
                Text(stringResource(R.string.pairing_pin_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::cancelPinRequest) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

private fun OpenFxHttpSender.PinCheck.messageRes(): Int = when (this) {
    OpenFxHttpSender.PinCheck.LOCKED -> R.string.pairing_pin_locked
    OpenFxHttpSender.PinCheck.UNREACHABLE -> R.string.pairing_pin_unreachable
    else -> R.string.pairing_pin_wrong
}
