package com.openfx.mobile.ui.transfer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.openfx.common.ui.dpadFieldNavigation
import com.openfx.core.sync.http.OpenFxDevice
import com.openfx.core.sync.http.OpenFxHttpSender
import com.openfx.mobile.R

/**
 * 以 IP 手動加入接收端 — 路由器擋住 UDP 廣播 (AP 隔離、mesh) 時使用
 *
 * [add] 會探測該 IP：找到 OpenFX 接收端就記住並回傳裝置，否則回傳 null。
 */
@Composable
fun ManualDeviceDialog(
    add: (ip: String, port: Int, onResult: (OpenFxDevice?) -> Unit) -> Unit,
    onAdded: (OpenFxDevice) -> Unit,
    onDismiss: () -> Unit
) {
    var input by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val invalid = stringResource(R.string.manual_device_invalid)
    val notFound = stringResource(R.string.manual_device_not_found, input.trim())

    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun submit() {
        if (checking) return
        val (ip, port) = parse(input) ?: run {
            error = invalid
            return
        }
        checking = true
        error = null
        add(ip, port) { device ->
            checking = false
            if (device != null) onAdded(device) else error = notFound
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.manual_device_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.manual_device_message))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.trim() },
                    label = { Text(stringResource(R.string.manual_device_ip)) },
                    placeholder = { Text("192.168.x.x") },
                    singleLine = true,
                    enabled = !checking,
                    // Ascii：注音/拼音鍵盤自動切英數
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .dpadFieldNavigation()
                )
                if (checking) {
                    Text(stringResource(R.string.manual_device_checking))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !checking && input.isNotBlank(), onClick = ::submit) {
                Text(stringResource(R.string.add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

private val HOST = Regex("^[A-Za-z0-9.-]+$")

/** 接受 "192.168.0.10" 或 "192.168.0.10:8989"；格式錯誤回傳 null */
internal fun parse(input: String): Pair<String, Int>? {
    val text = input.trim()
    val host = text.substringBefore(':')
    val port = if (':' in text) text.substringAfter(':').toIntOrNull() ?: return null else OpenFxHttpSender.DEFAULT_PORT
    if (host.isEmpty() || !HOST.matches(host) || port !in 1..65535) return null
    // 全部是數字與點時要像 IPv4
    if (host.all { it.isDigit() || it == '.' }) {
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() !in 0..255 }) return null
    }
    return host to port
}
