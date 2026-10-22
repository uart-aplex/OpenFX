package com.openfx.mobile.ui.browser

import androidx.compose.ui.res.stringResource
import com.openfx.mobile.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openfx.core.sync.http.OpenFxDevice

/**
 * 「傳送到裝置」對話框 — 列出掃描到的 OpenFX 接收端，點選即傳送
 */
@Composable
fun SendToDeviceDialog(
    devices: List<OpenFxDevice>,
    isScanning: Boolean,
    onRescan: () -> Unit,
    onSelect: (OpenFxDevice) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.control_send_to_tv)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    isScanning -> {
                        CircularProgressIndicator()
                        Text(
                            stringResource(R.string.send_scanning_receivers),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    devices.isEmpty() -> {
                        Text(
                            stringResource(R.string.send_no_receivers_phone),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> {
                        devices.forEach { device ->
                            OutlinedButton(
                                onClick = { onSelect(device) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    Icons.Default.Devices,
                                    contentDescription = null
                                )
                                Text(
                                    "  ${device.deviceName} · ${device.ip}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isScanning,
                onClick = onRescan
            ) { Text(stringResource(R.string.rescan)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}
