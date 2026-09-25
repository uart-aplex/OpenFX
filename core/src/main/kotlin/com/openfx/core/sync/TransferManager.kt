package com.openfx.core.sync

import com.openfx.core.sync.http.OpenFxDevice
import com.openfx.core.sync.http.OpenFxHttpReceiver
import com.openfx.core.sync.http.OpenFxHttpSender
import com.openfx.core.sync.http.UdpDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P2P 傳輸中樞 — 統一管理收發任務
 *
 * 發送：HTTP POST (port 8989)，支援進度回報與取消
 * 接收：OpenFxHttpReceiver，由 TransferService (前景服務) 持有，背景持續接收
 * 發現：UdpDiscovery (UDP broadcast)，2 秒內找到同網段的接收端
 *
 * UI (DeviceScreen / TransferScreen / FileBrowser) 透過 StateFlow 觀察任務狀態。
 * 任務列表的更新會從多個執行緒進來 (傳送 coroutine / 接收 socket thread)，
 * 一律用 StateFlow.update { } (CAS) 做原子更新，避免進度互相覆蓋遺失。
 */
@Singleton
class TransferManager @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var receiver: OpenFxHttpReceiver? = null
    private var advertiser: Closeable? = null
    private val uploads = ConcurrentHashMap<String, OpenFxHttpSender.UploadHandle>()

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    private val _receivedCount = MutableStateFlow(0)
    val receivedCount: StateFlow<Int> = _receivedCount.asStateFlow()

    private val _receiveDir = MutableStateFlow<String?>(null)
    val receiveDir: StateFlow<String?> = _receiveDir.asStateFlow()

    /** 最近完成接收的實際檔案路徑，供 UI 顯示後續操作（例如 APK 安裝）。 */
    private val _lastReceivedFilePath = MutableStateFlow<String?>(null)
    val lastReceivedFilePath: StateFlow<String?> = _lastReceivedFilePath.asStateFlow()

    private val _activeTasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val activeTasks: StateFlow<List<TransferTask>> = _activeTasks.asStateFlow()

    private val _history = MutableStateFlow<List<TransferTask>>(emptyList())
    val history: StateFlow<List<TransferTask>> = _history.asStateFlow()

    /** 最近一次事件訊息 (接收完成、錯誤等)，UI 狀態列用 */
    private val _lastEvent = MutableStateFlow<String?>(null)
    val lastEvent: StateFlow<String?> = _lastEvent.asStateFlow()

    // ===== 接收 =====

    @Synchronized
    fun startReceiver(
        deviceName: String,
        saveDir: File,
        port: Int = OpenFxHttpSender.DEFAULT_PORT
    ): Result<Unit> {
        if (receiver?.isRunning == true) return Result.success(Unit)

        return runCatching {
            saveDir.mkdirs()
            val instance = OpenFxHttpReceiver(
                deviceName = deviceName,
                saveDir = saveDir,
                port = port,
                onFileReceived = { file, senderDeviceName ->
                    _receivedCount.update { it + 1 }
                    _lastReceivedFilePath.value = file.absolutePath
                    _lastEvent.value = "已從 $senderDeviceName 接收 ${file.name}"
                },
                onError = { message ->
                    _lastEvent.value = "接收錯誤：$message"
                },
                onProgress = { fileName, received, total, senderDeviceName ->
                    updateReceiveProgress(fileName, received, total, senderDeviceName)
                },
                onFailed = { fileName, reason ->
                    markReceiveFailed(fileName, reason)
                }
            )
            instance.start()
            check(instance.isRunning) { "接收端啟動失敗 (port $port 被佔用?)" }

            advertiser = UdpDiscovery.startAdvertiser(deviceName, port)
            receiver = instance
            _isReceiving.value = true
            _receiveDir.value = saveDir.absolutePath
            _receivedCount.value = 0
        }
    }

    @Synchronized
    fun stopReceiver() {
        advertiser?.close()
        advertiser = null
        receiver?.stop()
        receiver = null
        _isReceiving.value = false
    }

    /**
     * 接收進度 — 接收端只有檔名可對應，用「同名且進行中」的 RECEIVE 任務來更新。
     * 第一個 chunk 時建立任務，收滿時標記完成並移入歷史。
     */
    private fun updateReceiveProgress(
        fileName: String,
        received: Long,
        total: Long,
        senderDeviceName: String
    ) {
        val done = total > 0 && received >= total
        var taskId: String? = null

        _activeTasks.update { list ->
            val existing = list.find {
                it.direction == TransferDirection.RECEIVE &&
                    it.fileName == fileName &&
                    it.status == TransferStatus.ACTIVE
            }

            if (existing == null) {
                val newTask = TransferTask(
                    id = UUID.randomUUID().toString().take(8),
                    fileName = fileName,
                    fileSize = total,
                    direction = TransferDirection.RECEIVE,
                    targetDevice = senderDeviceName,
                    status = if (done) TransferStatus.COMPLETED else TransferStatus.ACTIVE,
                    progress = if (total > 0) received.toFloat() / total else 0f,
                    bytesTransferred = received,
                    startTime = System.currentTimeMillis(),
                    endTime = if (done) System.currentTimeMillis() else 0L
                )
                taskId = newTask.id
                list + newTask
            } else {
                taskId = existing.id
                list.map {
                    if (it.id == existing.id) {
                        it.copy(
                            bytesTransferred = received,
                            progress = if (total > 0) received.toFloat() / total else 0f,
                            status = if (done) TransferStatus.COMPLETED else TransferStatus.ACTIVE,
                            endTime = if (done) System.currentTimeMillis() else 0L
                        )
                    } else it
                }
            }
        }

        if (done) taskId?.let { moveToHistory(it) }
    }

    /** 接收中斷 (對方斷線/逾時) — 標記失敗並移入歷史，避免任務永遠卡在進行中 */
    private fun markReceiveFailed(fileName: String, reason: String) {
        var taskId: String? = null
        _activeTasks.update { list ->
            val existing = list.find {
                it.direction == TransferDirection.RECEIVE &&
                    it.fileName == fileName &&
                    it.status == TransferStatus.ACTIVE
            } ?: return@update list

            taskId = existing.id
            list.map {
                if (it.id == existing.id) {
                    it.copy(status = TransferStatus.FAILED, endTime = System.currentTimeMillis())
                } else it
            }
        }
        taskId?.let { moveToHistory(it) }
        _lastEvent.value = "接收失敗：$fileName ($reason)"
    }

    // ===== 傳送 =====

    /** 傳送本機檔案，回傳建立的任務 ID 列表 */
    fun sendFiles(
        paths: List<String>,
        device: OpenFxDevice,
        senderDeviceName: String = "OpenFX Device"
    ): List<String> {
        return paths.map(::File)
            .filter { it.isFile }
            .map { file ->
                startUpload(file.name, file.length(), device) { handle, taskId ->
                    OpenFxHttpSender.uploadFile(
                        targetIp = device.ip,
                        port = device.port,
                        file = file,
                        senderDeviceName = senderDeviceName,
                        handle = handle,
                        onProgress = { sent -> updateSendProgress(taskId, sent, file.length()) }
                    )
                }
            }
    }

    /** 傳送 InputStream 內容 (SAF Uri 來源)，回傳任務 ID */
    fun sendStream(
        fileName: String,
        contentLength: Long,
        device: OpenFxDevice,
        senderDeviceName: String = "OpenFX Device",
        openStream: () -> InputStream
    ): String {
        return startUpload(fileName, contentLength, device) { handle, taskId ->
            openStream().use { input ->
                OpenFxHttpSender.uploadStream(
                    targetIp = device.ip,
                    port = device.port,
                    fileName = fileName,
                    contentLength = contentLength,
                    input = input,
                    senderDeviceName = senderDeviceName,
                    handle = handle,
                    onProgress = { sent -> updateSendProgress(taskId, sent, contentLength) }
                )
            }
        }
    }

    private fun startUpload(
        fileName: String,
        size: Long,
        device: OpenFxDevice,
        upload: (OpenFxHttpSender.UploadHandle, String) -> Boolean
    ): String {
        val taskId = UUID.randomUUID().toString().take(8)
        val task = TransferTask(
            id = taskId,
            fileName = fileName,
            fileSize = size,
            direction = TransferDirection.SEND,
            targetDevice = device.deviceName,
            status = TransferStatus.ACTIVE,
            startTime = System.currentTimeMillis()
        )
        _activeTasks.update { it + task }

        val handle = OpenFxHttpSender.UploadHandle()
        uploads[taskId] = handle

        scope.launch {
            try {
                val ok = upload(handle, taskId)
                finishSend(taskId, ok, size)
                if (!ok) _lastEvent.value = "傳送失敗：$fileName"
            } catch (_: OpenFxHttpSender.UploadCancelledException) {
                // cancelTask 已標記 CANCELLED 並移入歷史
            } catch (e: Exception) {
                if (!handle.isCancelled) {
                    updateTask(taskId) {
                        it.copy(status = TransferStatus.FAILED, endTime = System.currentTimeMillis())
                    }
                    moveToHistory(taskId)
                    _lastEvent.value = "傳送失敗：$fileName (${e.message ?: "連線錯誤"})"
                }
            } finally {
                uploads.remove(taskId)
            }
        }

        return taskId
    }

    private fun updateSendProgress(taskId: String, sent: Long, total: Long) {
        updateTask(taskId) {
            it.copy(
                bytesTransferred = sent,
                progress = if (total > 0) sent.toFloat() / total else 0f
            )
        }
    }

    private fun finishSend(taskId: String, ok: Boolean, total: Long) {
        updateTask(taskId) {
            it.copy(
                status = if (ok) TransferStatus.COMPLETED else TransferStatus.FAILED,
                progress = if (ok) 1f else it.progress,
                bytesTransferred = if (ok) total else it.bytesTransferred,
                endTime = System.currentTimeMillis()
            )
        }
        moveToHistory(taskId)
    }

    fun cancelTask(taskId: String) {
        uploads[taskId]?.cancel()
        updateTask(taskId) {
            it.copy(status = TransferStatus.CANCELLED, endTime = System.currentTimeMillis())
        }
        moveToHistory(taskId)
    }

    fun clearHistory() {
        _history.value = emptyList()
    }

    // ===== 裝置發現 =====

    /** UDP 廣播掃描，最多等 timeoutMs；阻塞時間內在 IO dispatcher 執行 */
    suspend fun discoverDevices(timeoutMs: Int = 2000): List<OpenFxDevice> =
        withContext(Dispatchers.IO) {
            UdpDiscovery.scan(timeoutMs)
        }

    // ===== Private =====

    private fun updateTask(taskId: String, update: (TransferTask) -> TransferTask) {
        _activeTasks.update { list ->
            list.map { if (it.id == taskId) update(it) else it }
        }
    }

    private fun moveToHistory(taskId: String) {
        var moved: TransferTask? = null
        _activeTasks.update { list ->
            val task = list.find { it.id == taskId }
            if (task == null) {
                list
            } else {
                moved = task
                list.filterNot { it.id == taskId }
            }
        }
        moved?.let { task -> _history.update { listOf(task) + it } }
    }
}
