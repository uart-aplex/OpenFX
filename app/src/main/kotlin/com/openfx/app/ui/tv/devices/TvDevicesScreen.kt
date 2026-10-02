package com.openfx.app.ui.tv.devices

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
    val lastEvent by viewModel.lastEvent.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val activeTasks by viewModel.activeTasks.collectAsState()
    val history by viewModel.history.collectAsState()
    val preparingApps by viewModel.preparingApps.collectAsState()
    val statusMessage by viewModel.statusMessage.collectAsState()
    var appSendTarget by remember { mutableStateOf<OpenFxDevice?>(null) }
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

    val statusText = when {
        receivingTask != null -> {
            val percent = (receivingTask.progress * 100).toInt().coerceIn(0, 100)
            "正在接收 ${receivingTask.fileName} · $percent%"
        }
        isReceiving && lastEvent != null -> "$lastEvent · 接收模式持續開啟"
        isReceiving -> "接收模式已啟動，手機現在可以掃描到這台 TV"
        isScanning -> "正在掃描同一個 Wi-Fi 裡的 OpenFX 裝置..."
        else -> "尚未開放接收手機檔案"
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
                Text("手機傳檔到 TV", style = MaterialTheme.typography.headlineLarge)
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
                Text("首頁")
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { onOpenReceivedFiles(receiveDirectory) }) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("查看已接收")
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { viewModel.scan() }, enabled = !isScanning) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (isScanning) "掃描中" else "掃描")
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
                    if (isReceiving) "接收模式已開啟" else "接收模式未開啟",
                    color = stateColor,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text("${viewModel.localIp ?: "取得 IP 中"}:8989  ·  已接收 $receivedCount 個", style = MaterialTheme.typography.bodyMedium)
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
                Text(if (isReceiving) "停止接收" else "開始接收手機檔案")
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
        Text("儲存位置：$receiveDirectory", style = MaterialTheme.typography.bodySmall)
        }

        item {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("其他接收裝置（TV 傳送用）", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onBrowseFilesToSend) {
                Icon(Icons.Default.Folder, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("選擇檔案傳送")
            }
        }
        }

        if (devices.isEmpty()) {
            item {
            Box(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Text(if (isScanning) "正在尋找接收裝置..." else "若要從 TV 傳檔，請先讓對方開啟接收模式，再按「選擇檔案傳送」。")
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
                                Text("${device.ip}:${device.port} · 可傳檔", style = MaterialTheme.typography.bodySmall)
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
                        activeTask != null -> "已連線：${activeTask.targetDevice}"
                        lastTask != null -> "最近連線：${lastTask.targetDevice}"
                        isReceiving -> "等待手機連線"
                        else -> "接收模式尚未啟動"
                    },
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    when {
                        activeTask != null -> "正在接收 ${activeTask.fileName}"
                        lastTask != null -> "已完成 ${lastTask.fileName}"
                        isReceiving -> "手機選擇檔案後，這裡會顯示即時進度"
                        else -> "尚無接收中的傳輸"
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
                    else "完成",
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}
