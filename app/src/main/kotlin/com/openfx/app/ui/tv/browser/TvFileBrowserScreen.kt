package com.openfx.app.ui.tv.browser

import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.openfx.core.file.FileItem
import com.openfx.core.file.FileType
import com.openfx.core.sync.http.LanDeviceScanner
import com.openfx.core.sync.http.OpenFxDevice
import com.openfx.core.sync.http.UdpDiscovery
import com.openfx.mobile.ui.browser.AudioPlayerDialog
import com.openfx.mobile.ui.browser.FileBrowserViewModel
import com.openfx.mobile.ui.transfer.TransferViewModel
import androidx.compose.runtime.collectAsState

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvFileBrowserScreen(
    onBackToHome: () -> Unit,
    initialPath: String? = null,
    viewModel: FileBrowserViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var selectedFile by remember { mutableStateOf<FileItem?>(null) }
    var showSendDialog by remember { mutableStateOf(false) }
    var audioPlaylist by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var audioIndex by remember { mutableStateOf<Int?>(null) }

    androidx.compose.runtime.LaunchedEffect(initialPath) {
        initialPath?.let { viewModel.navigateTo(it) }
    }

    BackHandler {
        if (!viewModel.navigateUp()) onBackToHome()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 28.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("檔案", style = MaterialTheme.typography.headlineLarge)
                Text(
                    text = uiState.currentPath,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.width(24.dp))
            Button(onClick = {
                if (!viewModel.navigateUp()) onBackToHome()
            }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("返回")
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { viewModel.navigateTo(uiState.currentPath) }) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("重新整理")
            }
            Spacer(Modifier.width(12.dp))
            Button(
                enabled = selectedFile?.isDirectory == false,
                onClick = {
                    selectedFile?.let { file ->
                        if (file.fileType == FileType.AUDIO) {
                            audioPlaylist = uiState.files.filter { !it.isDirectory && it.fileType == FileType.AUDIO }
                            audioIndex = audioPlaylist.indexOfFirst { it.path == file.path }.takeIf { it >= 0 }
                        } else {
                            viewModel.openFile(file)
                        }
                    }
                }
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("開啟/安裝")
            }
            Spacer(Modifier.width(12.dp))
            Button(
                enabled = selectedFile?.isDirectory == false,
                onClick = { showSendDialog = true }
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("傳送")
            }
        }

        selectedFile?.let { file ->
            if (!file.isDirectory) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "已選取：${file.name}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            uiState.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(uiState.error ?: "無法讀取資料夾")
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.navigateTo(uiState.currentPath) }) {
                            Text("重試")
                        }
                    }
                }
            }

            uiState.files.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("這個資料夾是空的")
                }
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 220.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(uiState.files, key = { it.path }) { file ->
                        TvFileItemCard(
                            file = file,
                            isSelected = selectedFile?.path == file.path,
                            onClick = {
                                if (file.isDirectory) {
                                    selectedFile = null
                                    viewModel.navigateTo(file.path)
                                } else {
                                    selectedFile = file
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showSendDialog && selectedFile != null) {
        TvSendFileDialog(
            file = selectedFile!!,
            onDismiss = { showSendDialog = false }
        )
    }

    audioIndex?.let { index ->
        AudioPlayerDialog(
            playlist = audioPlaylist,
            currentIndex = index,
            onIndexChange = { audioIndex = it },
            onDismiss = { audioIndex = null }
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvFileItemCard(
    file: FileItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.size(width = 220.dp, height = 132.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = file.fileType.tvIcon,
                contentDescription = null,
                modifier = Modifier.size(36.dp)
            )
            Column {
                Text(
                    text = file.name.ifEmpty { "/" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = when {
                        isSelected -> "已選取 · ${file.formattedSize}"
                        file.isDirectory -> "資料夾"
                        else -> file.formattedSize
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun TvSendFileDialog(
    file: FileItem,
    onDismiss: () -> Unit,
    transferViewModel: TransferViewModel = hiltViewModel()
) {
    var devices by remember { mutableStateOf<List<OpenFxDevice>>(emptyList()) }
    var statusText by remember { mutableStateOf("掃描附近接收端...") }
    var isScanning by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }

    fun scanDevices() {
        if (isScanning) return
        isScanning = true
        statusText = "掃描附近接收端..."
        Thread {
            val localIp = LanDeviceScanner.getLocalIpAddress()
            val udpFound = UdpDiscovery.scan(timeoutMs = 2500)
                .filterNot { it.ip == localIp }
            val found = if (udpFound.isNotEmpty()) {
                udpFound
            } else {
                LanDeviceScanner.scanSameSubnet(timeoutMs = 700, retryCount = 1, batchSize = 48)
                    .filterNot { it.ip == localIp }
            }

            Handler(Looper.getMainLooper()).post {
                devices = found
                isScanning = false
                statusText = if (found.isEmpty()) {
                    "沒有找到裝置。請先在手機 OpenFX 按「我要接收」。"
                } else {
                    "找到 ${found.size} 個裝置"
                }
            }
        }.start()
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        isScanning = false
        scanDevices()
    }

    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        title = { androidx.compose.material3.Text("傳送檔案") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.Text(
                    file.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                androidx.compose.material3.Text(statusText)

                if (isScanning || isSending) {
                    CircularProgressIndicator()
                }

                devices.forEach { device ->
                    androidx.compose.material3.OutlinedButton(
                        enabled = !isSending,
                        onClick = {
                            // 交給 TransferManager：需要配對碼時會跳出輸入框，進度顯示在「裝置」頁
                            transferViewModel.sendFiles(listOf(file.path), device)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        androidx.compose.material3.Text("${device.deviceName} · ${device.ip}")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !isScanning && !isSending,
                onClick = { scanDevices() }
            ) {
                androidx.compose.material3.Text("重新掃描")
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSending,
                onClick = onDismiss
            ) {
                androidx.compose.material3.Text("關閉")
            }
        }
    )
}

private val FileType.tvIcon: ImageVector
    get() = when (this) {
        FileType.FOLDER -> Icons.Default.Folder
        FileType.IMAGE -> Icons.Default.Image
        FileType.VIDEO -> Icons.Default.VideoFile
        FileType.AUDIO -> Icons.Default.AudioFile
        FileType.DOCUMENT -> Icons.AutoMirrored.Filled.InsertDriveFile
        FileType.ARCHIVE -> Icons.Default.FolderZip
        FileType.APK -> Icons.Default.Android
        FileType.CODE -> Icons.Default.Code
        FileType.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
