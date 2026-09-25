package com.openfx.mobile.ui.browser

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
        title = { Text("傳送到裝置") },
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
                            "掃描附近接收端…",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    devices.isEmpty() -> {
                        Text(
                            "沒有找到裝置。\n請在另一台裝置的 OpenFX「裝置」頁按「我要接收」。",
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
            ) { Text("重新掃描") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
