package com.openfx.app.ui.tv.devices

import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import com.openfx.mobile.ui.transfer.message
import android.os.Environment
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ButtonDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import com.openfx.core.sync.TransferDirection
import com.openfx.core.sync.TransferStatus
import com.openfx.core.sync.TransferTask
import com.openfx.mobile.ui.transfer.TransferViewModel
import com.openfx.core.sync.http.OpenFxDevice
import com.openfx.mobile.apps.InstalledAppsDialog
import com.openfx.mobile.R
import java.io.File

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvDevicesScreen(
    onBackToHome: () -> Unit,
    onOpenReceivedFiles: (String) -> Unit,
    onBrowseFilesToSend: () -> Unit,
    viewModel: TransferViewModel = hiltViewModel()
) {
    val isReceiving by viewModel.isReceiving.collectAsState()
    val receivedCount by viewModel.receivedCount.collectAsState()
    val receiveDir by viewModel.receiveDir.collectAsState()
    val receivePin by viewModel.receivePin.collectAsState()
    val lastEvent by viewModel.lastEvent.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val activeTasks by viewModel.activeTasks.collectAsState()
    val history by viewModel.history.collectAsState()
    val preparingApps by viewModel.preparingApps.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    var appSendTarget by remember { mutableStateOf<OpenFxDevice?>(null) }
    var showManual by remember { mutableStateOf(false) }
    val receiveButtonFocusRequester = remember { FocusRequester() }
    val receivingTask = activeTasks.firstOrNull {
        it.direction == TransferDirection.RECEIVE && it.status == TransferStatus.ACTIVE
    }
    val lastReceivedTask = history.firstOrNull {
        it.direction == TransferDirection.RECEIVE
    }
    val receiveDirectory = receiveDir ?: File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "OpenFX"
    ).absolutePath

    val resources = LocalResources.current
    val event = lastEvent
    val statusText = when {
        receivingTask != null -> {
            val percent = (receivingTask.progress * 100).toInt().coerceIn(0, 100)
            stringResource(R.string.tv_status_receiving, receivingTask.fileName, percent)
        }
        isReceiving && event != null ->
            stringResource(R.string.tv_status_event_receiving, event.message(resources))
        isReceiving -> stringResource(R.string.tv_status_receive_on)
        isScanning -> stringResource(R.string.tv_status_scanning)
        else -> stringResource(R.string.tv_status_receive_off)
    }

    LaunchedEffect(Unit) {
        receiveButtonFocusRequester.requestFocus()
        viewModel.scan()
    }

    BackHandler(onBack = onBackToHome)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.tv_phone_to_tv), style = MaterialTheme.typography.headlineLarge)
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(24.dp))
            Button(onClick = onBackToHome) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tv_home))
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { onOpenReceivedFiles(receiveDirectory) }) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tv_view_received))
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { showManual = true }) {
                Text(stringResource(R.string.manual_device_add))
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { viewModel.scan() }, enabled = !isScanning) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (isScanning) R.string.scan_in_progress_short else R.string.scan))
            }
        }
        }

        item {
        val stateColor = if (isReceiving) Color(0xFF57E3A1) else Color(0xFFFFC857)
        Row(
            modifier = Modifier.fillMaxWidth()
                .background(if (isReceiving) Color(0xFF143C2C) else Color(0xFF332C1D))
                .border(2.dp, stateColor)
                .padding(24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Icon(Icons.Default.Download, contentDescription = null, tint = stateColor, modifier = Modifier.size(48.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (isReceiving) R.string.tv_receive_mode_on else R.string.tv_receive_mode_off),
                    color = stateColor,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    pluralStringResource(
                        R.plurals.tv_address_received,
                        receivedCount,
                        viewModel.localIp ?: stringResource(R.string.tv_getting_ip),
                        8989,
                        receivedCount
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                receivePin?.let { pin ->
                    // 遙控器距離遠 — 配對碼用大字顯示，手機端輸入時要看得清楚
                    Text(
                        stringResource(R.string.pairing_pin_display, pin),
                        color = stateColor,
                        style = MaterialTheme.typography.displaySmall
                    )
                }
            }
            if (isReceiving) {
                Button(onClick = viewModel::regenerateReceivePin) {
                    Text(stringResource(R.string.pairing_pin_regenerate))
                }
            }
            Button(
                onClick = { if (isReceiving) viewModel.stopReceive() else viewModel.startReceive() },
                modifier = Modifier.focusRequester(receiveButtonFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = if (isReceiving) Color(0xFF9E3535) else Color(0xFF57E3A1),
                    contentColor = if (isReceiving) Color.White else Color(0xFF062B1C),
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color(0xFF102018)
                )
            ) {
                Icon(if (isReceiving) Icons.Default.Stop else Icons.Default.Download, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (isReceiving) R.string.receive_stop else R.string.tv_start_receive))
            }
        }
        }

        item {
        TransferConnectionPanel(
            activeTask = receivingTask,
            lastTask = lastReceivedTask,
            isReceiving = isReceiving
        )
        }

        item {
        Text(stringResource(R.string.receive_save_dir, receiveDirectory), style = MaterialTheme.typography.bodySmall)
        }

        item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.tv_other_receivers), style = MaterialTheme.typography.titleLarge)
            Button(onClick = onBrowseFilesToSend) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.tv_choose_files_to_send))
            }
        }
        }

        if (devices.isEmpty()) {
            item {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(if (isScanning) R.string.tv_finding_receivers else R.string.tv_send_hint))
            }
            }
        } else {
                items(devices, key = { it.ip }) { device ->
                    Card(
                        onClick = onBrowseFilesToSend,
                        modifier = Modifier.fillMaxWidth().height(88.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize().padding(18.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(36.dp))
                            Column(Modifier.weight(1f)) {
                                Text(device.deviceName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.tv_device_ready, device.ip, device.port),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Button(onClick = {
                                viewModel.consumeStatusMessage()
                                appSendTarget = device
                            }) {
                                Icon(Icons.Default.Android, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.apps_send_installed))
                            }
                        }
                    }
                }
        }
    }

    if (showManual) {
        com.openfx.mobile.ui.transfer.ManualDeviceDialog(
            add = viewModel::addManualDevice,
            onAdded = { showManual = false },
            onDismiss = { showManual = false }
        )
    }

    appSendTarget?.let { device ->
        InstalledAppsDialog(
            deviceName = device.deviceName,
            preparing = preparingApps,
            status = statusMessage,
            onDismiss = { viewModel.cancelAppPreparation(); appSendTarget = null },
            onSend = { packages ->
                viewModel.sendInstalledApps(packages, device) { appSendTarget = null }
            }
        )
    }
}

@Composable
private fun TransferConnectionPanel(
    activeTask: TransferTask?,
    lastTask: TransferTask?,
    isReceiving: Boolean
) {
    val task = activeTask ?: lastTask
    val progress = activeTask?.progress?.coerceIn(0f, 1f) ?: if (lastTask != null) 1f else 0f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(118.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Icon(Icons.Default.PhoneAndroid, contentDescription = null, modifier = Modifier.size(42.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    when {
                        activeTask != null -> stringResource(R.string.tv_conn_active, activeTask.targetDevice)
                        lastTask != null -> stringResource(R.string.tv_conn_last, lastTask.targetDevice)
                        isReceiving -> stringResource(R.string.tv_conn_waiting)
                        else -> stringResource(R.string.tv_conn_receive_off)
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    when {
                        activeTask != null -> stringResource(R.string.tv_conn_receiving_file, activeTask.fileName)
                        lastTask != null -> stringResource(R.string.tv_conn_finished_file, lastTask.fileName)
                        isReceiving -> stringResource(R.string.tv_conn_progress_hint)
                        else -> stringResource(R.string.tv_conn_idle)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (task != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            if (task != null) {
                Text(
                    if (activeTask != null) "${(progress * 100).toInt()}%"
                    else stringResource(R.string.done),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
