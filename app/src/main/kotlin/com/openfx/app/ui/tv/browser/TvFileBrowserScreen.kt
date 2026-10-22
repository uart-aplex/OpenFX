package com.openfx.app.ui.tv.browser

import com.openfx.mobile.ui.vault.VaultActivity
import androidx.compose.material.icons.filled.Lock
import com.openfx.mobile.ui.network.NetworkServersDialog
import com.openfx.mobile.ui.browser.RemoteFetchBar
import com.openfx.core.network.smb.SmbPath
import androidx.compose.material.icons.filled.Lan
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.openfx.mobile.R
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
    openNetworkOnStart: Boolean = false,
    viewModel: FileBrowserViewModel = hiltViewModel()
) {
    var showNetwork by remember { mutableStateOf(openNetworkOnStart) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var selectedFile by remember { mutableStateOf<FileItem?>(null) }
    var showSendDialog by remember { mutableStateOf(false) }
    var audioPlaylist by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var audioIndex by remember { mutableStateOf<Int?>(null) }

    androidx.compose.runtime.LaunchedEffect(initialPath) {
        initialPath?.let { viewModel.navigateTo(it) }
    }

    // 換資料夾後清掉選取，避免「開啟」作用在上一個資料夾的檔案
    androidx.compose.runtime.LaunchedEffect(uiState.currentPath) {
        selectedFile = null
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
                Text(stringResource(R.string.nav_files), style = MaterialTheme.typography.headlineLarge)
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
                Text(stringResource(R.string.back))
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { showNetwork = true }) {
                Icon(Icons.Default.Lan, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.network_chip))
            }
            Spacer(Modifier.width(12.dp))
            Button(onClick = { viewModel.navigateTo(uiState.currentPath) }) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.refresh))
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
                Text(stringResource(R.string.tv_open_or_install))
            }
            Spacer(Modifier.width(12.dp))
            Button(
                enabled = selectedFile?.let { !SmbPath.isSmb(it.path) } == true,
                onClick = {
                    selectedFile?.let { file ->
                        // TV 上預設保留原檔 (遙控器誤按比較難救回)，加密後可自行刪除
                        context.startActivity(VaultActivity.importIntent(context, listOf(file.path), deleteOriginals = false))
                    }
                }
            ) {
                Icon(Icons.Default.Lock, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.vault_encrypt_action))
            }
            Spacer(Modifier.width(12.dp))
            Button(
                // OpenFX 傳檔只支援本機檔案；網路上的檔案請先複製到本機
                enabled = selectedFile?.let { !it.isDirectory && !SmbPath.isSmb(it.path) } == true,
                onClick = { showSendDialog = true }
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.send))
            }
        }

        selectedFile?.let { file ->
            if (!file.isDirectory) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.selected_file, file.name),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        RemoteFetchBar(uiState.remoteFetch)

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
                        Text(uiState.error ?: stringResource(R.string.folder_read_failed))
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.navigateTo(uiState.currentPath) }) {
                            Text(stringResource(R.string.retry))
                        }
                    }
                }
            }

            uiState.files.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.browser_folder_empty))
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

    if (showNetwork) {
        NetworkServersDialog(
            onOpen = { path ->
                showNetwork = false
                selectedFile = null
                viewModel.navigateTo(path)
            },
            onDismiss = { showNetwork = false }
        )
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
                        isSelected -> stringResource(R.string.tv_selected_size, file.formattedSize)
                        file.isDirectory -> stringResource(R.string.folder)
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
    var isScanning by remember { mutableStateOf(true) }
    var isSending by remember { mutableStateOf(false) }

    fun scanDevices() {
        if (isScanning) return
        isScanning = true
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
            }
        }.start()
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        isScanning = false
        scanDevices()
    }

    val statusText = when {
        isScanning -> stringResource(R.string.send_scanning_receivers)
        devices.isEmpty() -> stringResource(R.string.send_no_receivers_tv)
        else -> pluralStringResource(R.plurals.send_found_devices, devices.size, devices.size)
    }

    AlertDialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        title = { androidx.compose.material3.Text(stringResource(R.string.send_files)) },
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
                androidx.compose.material3.Text(stringResource(R.string.rescan))
            }
        },
        dismissButton = {
            TextButton(
                enabled = !isSending,
                onClick = onDismiss
            ) {
                androidx.compose.material3.Text(stringResource(R.string.close))
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
