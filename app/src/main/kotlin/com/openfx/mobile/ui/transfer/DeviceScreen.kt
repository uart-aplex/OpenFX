package com.openfx.mobile.ui.transfer

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.openfx.core.sync.http.OpenFxDevice
import com.openfx.mobile.apps.InstalledAppsDialog
import com.openfx.mobile.R

/**
 * 裝置頁 — 接收開關 + 掃描附近 OpenFX 裝置 + 傳送檔案
 *
 * 接收模式由 TransferService (前景服務) 持有，離開頁面不會中斷。
 * 掃描走 UDP broadcast (2 秒)，對方需先按「我要接收」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScreen(
    onTransferStarted: () -> Unit = {},
    viewModel: TransferViewModel = hiltViewModel()
) {
    val isReceiving by viewModel.isReceiving.collectAsState()
    val receivedCount by viewModel.receivedCount.collectAsState()
    val receiveDir by viewModel.receiveDir.collectAsState()
    val receivePin by viewModel.receivePin.collectAsState()
    val lastEvent by viewModel.lastEvent.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    val activeTasks by viewModel.activeTasks.collectAsState()
    val preparingApps by viewModel.preparingApps.collectAsState()

    var selectedDevice by remember { mutableStateOf<OpenFxDevice?>(null) }
    var pendingSendTarget by remember { mutableStateOf<OpenFxDevice?>(null) }
    var appSendTarget by remember { mutableStateOf<OpenFxDevice?>(null) }
    val resources = LocalResources.current

    val sendPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        val target = pendingSendTarget
        when {
            uris.isNullOrEmpty() -> viewModel.reportStatus(resources.getString(R.string.send_pick_cancelled))
            target == null -> viewModel.reportStatus(resources.getString(R.string.send_target_lost))
            else -> {
                viewModel.sendUris(uris, target)
                onTransferStarted()
            }
        }
        pendingSendTarget = null
    }

    LaunchedEffect(Unit) { viewModel.scan() }

    Column(modifier = Modifier.fillMaxSize()) {
        // ===== 操作按鈕列 =====
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                modifier = Modifier.weight(1f),
                colors = if (isReceiving) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                } else ButtonDefaults.buttonColors(),
                onClick = { if (isReceiving) viewModel.stopReceive() else viewModel.startReceive() }
            ) {
                Text(stringResource(if (isReceiving) R.string.receive_stop else R.string.receive_start))
            }
            Button(
                modifier = Modifier.weight(1f),
                enabled = !isScanning,
                onClick = { viewModel.scan() }
            ) {
                Text(stringResource(if (isScanning) R.string.scan_in_progress else R.string.device_scan_nearby))
            }
        }

        // ===== 狀態文字 =====
        val localIpText = stringResource(
            R.string.device_local_ip,
            viewModel.localIp ?: stringResource(R.string.device_ip_unknown)
        )
        val receivingPortText = stringResource(R.string.device_receiving_port, 8989)
        val lastEventText = lastEvent?.message(resources)
        val statusText = buildString {
            append(localIpText)
            if (isReceiving) append(receivingPortText)
            lastEventText?.let { append("\n$it") }
            statusMessage?.let { append("\n$it") }
        }
        Text(
            statusText,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        // ===== 接收狀態卡片 =====
        if (isReceiving) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(stringResource(R.string.receive_mode_on), fontWeight = FontWeight.Medium)
                    receivePin?.let { pin ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.pairing_pin_display, pin),
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            androidx.compose.material3.TextButton(onClick = viewModel::regenerateReceivePin) {
                                Text(stringResource(R.string.pairing_pin_regenerate))
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.receive_save_dir, receiveDir ?: "Download/OpenFX"),
                        fontSize = 12.sp
                    )
                    Text(
                        pluralStringResource(R.plurals.receive_count_session, receivedCount, receivedCount),
                        fontSize = 12.sp
                    )
                    Text(
                        stringResource(R.string.receive_background_hint),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Text(
                stringResource(R.string.receive_tip_peer_first),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        // ===== 進行中的傳輸 (即時進度直接顯示在這裡) =====
        if (activeTasks.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                activeTasks.take(3).forEach { task ->
                    TransferTaskItem(
                        task = task,
                        onCancel = { viewModel.cancelTask(task.id) }
                    )
                }
                if (activeTasks.size > 3) {
                    Text(
                        pluralStringResource(
                            R.plurals.transfer_more_tasks, activeTasks.size - 3, activeTasks.size - 3
                        ),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
            }
        }

        // ===== 雷達 + 裝置列表 =====
        NearbyDeviceList(
            devices = devices,
            isScanning = isScanning,
            onDeviceClick = { selectedDevice = it },
            modifier = Modifier.weight(1f)
        )
    }

    // ===== 點選裝置：傳送面板 =====
    selectedDevice?.let { device ->
        ModalBottomSheet(onDismissRequest = { selectedDevice = null }) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                ListItem(
                    leadingContent = {
                        Icon(
                            Icons.Default.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    },
                    headlineContent = { Text(device.deviceName) },
                    supportingContent = {
                        Text("${device.ip}:${device.port}", fontSize = 12.sp)
                    }
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        pendingSendTarget = device
                        selectedDevice = null
                        sendPicker.launch(arrayOf("*/*"))
                    }
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.send_files_multi))
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        viewModel.consumeStatusMessage()
                        appSendTarget = device
                        selectedDevice = null
                    }
                ) {
                    Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.apps_send_installed))
                }
                Text(
                    stringResource(R.string.send_progress_hint),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }

    appSendTarget?.let { device ->
        InstalledAppsDialog(
            deviceName = device.deviceName,
            preparing = preparingApps,
            status = statusMessage,
            onDismiss = {
                viewModel.cancelAppPreparation()
                appSendTarget = null
            },
            onSend = { packages ->
                viewModel.sendInstalledApps(packages, device) {
                    appSendTarget = null
                    onTransferStarted()
                }
            }
        )
    }

}

// ===== 雷達動畫 + 附近裝置列表 =====

@Composable
private fun NearbyDeviceList(
    devices: List<OpenFxDevice>,
    isScanning: Boolean,
    onDeviceClick: (OpenFxDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    val sweep = rememberInfiniteTransition(label = "radar")
    val angle by sweep.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sweep"
    )

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 雷達動畫區域
        item {
            Box(
                modifier = Modifier
                    .size(200.dp)
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val center = Offset(size.width / 2, size.height / 2)
                    val maxRadius = size.minDimension / 2

                    for (i in 1..3) {
                        drawCircle(
                            color = Color.Gray.copy(alpha = 0.2f),
                            radius = maxRadius * i / 3,
                            center = center,
                            style = Stroke(width = 1f)
                        )
                    }

                    drawCircle(
                        color = Color(0xFF1976D2),
                        radius = 6f,
                        center = center
                    )

                    if (isScanning) {
                        val rad = Math.toRadians(angle.toDouble())
                        val endX = center.x + maxRadius * kotlin.math.cos(rad).toFloat()
                        val endY = center.y + maxRadius * kotlin.math.sin(rad).toFloat()
                        drawLine(
                            color = Color(0xFF1976D2).copy(alpha = 0.6f),
                            start = center,
                            end = Offset(endX, endY),
                            strokeWidth = 2f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                Text(
                    pluralStringResource(R.plurals.device_found_count, devices.size, devices.size),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                )
            }
        }

        item {
            Text(
                stringResource(R.string.device_nearby_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        if (devices.isEmpty()) {
            item {
                Text(
                    stringResource(if (isScanning) R.string.device_searching else R.string.device_none_found),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        items(devices, key = { it.ip }) { device ->
            ListItem(
                modifier = Modifier.clickable { onDeviceClick(device) },
                leadingContent = {
                    Icon(
                        Icons.Default.Devices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                },
                headlineContent = { Text(device.deviceName) },
                supportingContent = {
                    Text(
                        "${device.ip}:${device.port}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingContent = {
                    Icon(
                        Icons.Default.Upload,
                        contentDescription = stringResource(R.string.send_files),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
            HorizontalDivider()
        }
    }
}
