package com.openfx.mobile.ui.transfer

import com.openfx.core.sync.TransferEvent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfx.core.sync.PinRequest
import com.openfx.core.sync.TransferManager
import com.openfx.core.sync.TransferTask
import com.openfx.core.sync.http.LanDeviceScanner
import com.openfx.core.sync.http.OpenFxDevice
import com.openfx.core.settings.SettingsRepository
import com.openfx.mobile.R
import com.openfx.mobile.apps.InstalledAppsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import javax.inject.Inject

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val transferManager: TransferManager,
    private val settings: SettingsRepository,
    private val installedApps: InstalledAppsRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    // ===== TransferManager 狀態 ( Singleton，跨頁面共享 ) =====
    val isReceiving: StateFlow<Boolean> = transferManager.isReceiving
    val receivedCount: StateFlow<Int> = transferManager.receivedCount
    val receiveDir: StateFlow<String?> = transferManager.receiveDir
    val lastReceivedFilePath: StateFlow<String?> = transferManager.lastReceivedFilePath
    val pendingAppInstalls = transferManager.pendingAppInstalls

    fun consumeReceivedApp(path: String) = transferManager.consumeReceivedApp(path)
    val activeTasks: StateFlow<List<TransferTask>> = transferManager.activeTasks
    val history: StateFlow<List<TransferTask>> = transferManager.history
    val lastEvent: StateFlow<TransferEvent?> = transferManager.lastEvent

    // ===== 配對碼 =====
    val pinRequest: StateFlow<PinRequest?> = transferManager.pinRequest
    val receivePin: StateFlow<String?> = transferManager.receivePin

    fun submitPin(pin: String) = transferManager.submitPin(pin)

    fun cancelPinRequest() = transferManager.cancelPinRequest()

    fun regenerateReceivePin() {
        viewModelScope.launch {
            transferManager.updateReceivePin(settings.regenerateReceivePin())
        }
    }

    // ===== 掃描結果 (畫面層狀態) =====
    private val _devices = MutableStateFlow<List<OpenFxDevice>>(emptyList())
    val devices: StateFlow<List<OpenFxDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _preparingApps = MutableStateFlow(false)
    val preparingApps = _preparingApps.asStateFlow()
    private var appExportJob: Job? = null

    val localIp: String?
        get() = LanDeviceScanner.getLocalIpAddress()

    /** 大小不明的 Uri 先複製到這裡取得確切長度 (cacheDir,系統會自動清理) */
    private val spoolDir: File
        get() = File(appContext.cacheDir, "upload_spool").apply { mkdirs() }

    init {
        // 清掉上次殘留的暫存檔 (已傳完的暫存不主動刪,靠每次啟動時清)
        viewModelScope.launch(Dispatchers.IO) {
            spoolDir.listFiles()?.forEach { it.delete() }
        }
    }

    // ===== 接收 =====

    fun startReceive() {
        val intent = Intent(appContext, TransferService::class.java)
            .setAction(TransferService.ACTION_START_RECEIVE)
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun stopReceive() {
        val intent = Intent(appContext, TransferService::class.java)
            .setAction(TransferService.ACTION_STOP_RECEIVE)
        appContext.startService(intent)
    }

    // ===== 掃描 =====

    fun scan() {
        if (_isScanning.value) return
        _isScanning.value = true
        viewModelScope.launch {
            val myIp = LanDeviceScanner.getLocalIpAddress()
            val udpFound = transferManager.discoverDevices()
                .filterNot { it.ip == myIp }
            _devices.value = if (udpFound.isNotEmpty()) {
                udpFound
            } else {
                LanDeviceScanner.scanSameSubnet(
                    timeoutMs = 700,
                    retryCount = 1,
                    batchSize = 48
                ).filterNot { it.ip == myIp }
            }
            _isScanning.value = false
        }
    }

    /** 以 IP 手動加入；成功時同時放進目前的裝置清單 */
    fun addManualDevice(ip: String, port: Int, onResult: (OpenFxDevice?) -> Unit) {
        viewModelScope.launch {
            val device = transferManager.addManualDevice(ip, port)
            if (device != null) _devices.value = listOf(device) + _devices.value.filterNot { it.ip == device.ip }
            onResult(device)
        }
    }

    // ===== 傳送 =====

    fun sendInstalledApps(packageNames: List<String>, device: OpenFxDevice, onQueued: () -> Unit) {
        if (_preparingApps.value || packageNames.isEmpty()) return
        _preparingApps.value = true
        appExportJob = viewModelScope.launch(Dispatchers.IO) {
            var queued = 0
            var failed = 0
            try {
                val senderName = settings.deviceName.first()
                packageNames.distinct().forEachIndexed { index, packageName ->
                    _statusMessage.value = appContext.getString(
                        R.string.apps_preparing_progress, index + 1, packageNames.size
                    )
                    var exported: File? = null
                    try {
                        val file = installedApps.export(packageName)
                        exported = file
                        transferManager.sendStream(
                            fileName = file.name,
                            contentLength = file.length(),
                            device = device,
                            senderDeviceName = senderName,
                            onFinished = { file.delete(); file.parentFile?.delete() }
                        ) { file.inputStream() }
                        exported = null // TransferManager now owns the snapshot until upload ends.
                        queued++
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        failed++
                    } finally {
                        exported?.let { it.delete(); it.parentFile?.delete() }
                    }
                }
                _statusMessage.value = if (queued == 0) {
                    appContext.getString(R.string.apps_export_failed)
                } else {
                    appContext.getString(R.string.apps_queued, queued, failed, device.deviceName)
                }
                if (queued > 0) withContext(Dispatchers.Main) { onQueued() }
            } finally {
                _preparingApps.value = false
            }
        }
    }

    fun cancelAppPreparation() {
        appExportJob?.cancel()
    }

    fun sendFiles(paths: List<String>, device: OpenFxDevice) {
        viewModelScope.launch {
            transferManager.sendFiles(paths, device, settings.deviceName.first())
        }
    }

    fun sendUris(uris: List<Uri>, device: OpenFxDevice) {
        _statusMessage.value = appContext.resources.getQuantityString(
            R.plurals.send_preparing_files, uris.size, uris.size
        )
        viewModelScope.launch(Dispatchers.IO) {
            var queued = 0
            var failed = 0
            val senderDeviceName = settings.deviceName.first()

            uris.forEach { uri ->
                runCatching {
                    runCatching {
                        appContext.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }

                    val name = getDisplayName(appContext, uri)
                    val size = getContentSize(appContext, uri) ?: getLengthFromFileDescriptor(uri)

                    if (size != null && size > 0) {
                        transferManager.sendStream(
                            fileName = name,
                            contentLength = size,
                            device = device,
                            senderDeviceName = senderDeviceName
                        ) {
                            appContext.contentResolver.openInputStream(uri)
                                ?: throw FileNotFoundException("Cannot open $name")
                        }
                    } else {
                        val temp = spoolToTemp(uri, name)
                        check(temp.length() > 0) { "File is empty" }
                        transferManager.sendFiles(
                            listOf(temp.absolutePath),
                            device,
                            senderDeviceName
                        )
                    }
                    queued++
                }.onFailure {
                    failed++
                }
            }

            _statusMessage.value = when {
                queued > 0 && failed > 0 -> appContext.resources.getQuantityString(
                    R.plurals.send_queued_with_failures, queued, queued, failed
                )
                queued > 0 -> appContext.resources.getQuantityString(
                    R.plurals.send_queued_to, queued, queued, device.deviceName
                )
                else -> appContext.getString(R.string.send_unreadable)
            }
        }
    }

    fun reportStatus(message: String) {
        _statusMessage.value = message
    }

    fun cancelTask(taskId: String) = transferManager.cancelTask(taskId)

    fun clearHistory() = transferManager.clearHistory()

    fun consumeStatusMessage() {
        _statusMessage.value = null
    }

    // ===== SAF Uri helpers =====

    private fun getDisplayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(nameIndex) ?: "openfx_file"
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: "openfx_file"
    }

    private fun getContentSize(context: Context, uri: Uri): Long? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                    val size = cursor.getLong(sizeIndex)
                    if (size >= 0L) return size
                }
            }
        return null
    }

    /** 部分 SAF 提供者 query 不到大小，但 openAssetFileDescriptor 可以取得 */
    private fun getLengthFromFileDescriptor(uri: Uri): Long? {
        return runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length.takeIf { it > 0 }
            }
        }.getOrNull()
    }

    private fun spoolToTemp(uri: Uri, name: String): File {
        val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        val temp = File(spoolDir, "${System.currentTimeMillis()}_$safeName")
        appContext.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw FileNotFoundException("Cannot open $name")
        return temp
    }
}
