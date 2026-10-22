package com.openfx.core.sync

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import com.openfx.core.sync.http.LanDeviceScanner
import com.openfx.core.settings.SettingsRepository
import com.openfx.core.sync.http.OpenFxDevice
import com.openfx.core.sync.http.OpenFxHttpReceiver
import com.openfx.core.sync.http.OpenFxHttpSender
import com.openfx.core.sync.http.UdpDiscovery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.File
import java.io.IOException
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
 * 配對：接收端有 6 位數配對碼；傳送前先用 /auth 確認已存的配對碼，
 *       無效時透過 [pinRequest] 請 UI 跳出輸入框，驗證成功後依對方 deviceId 記住
 *
 * UI (DeviceScreen / TransferScreen / FileBrowser) 透過 StateFlow 觀察任務狀態。
 * 任務列表的更新會從多個執行緒進來 (傳送 coroutine / 接收 socket thread)，
 * 一律用 StateFlow.update { } (CAS) 做原子更新，避免進度互相覆蓋遺失。
 */
/** 等待使用者輸入配對碼的請求 — UI 觀察 [TransferManager.pinRequest] 顯示對話框 */
data class PinRequest(
    val device: OpenFxDevice,
    /** 上一次輸入的結果 (WRONG / LOCKED / UNREACHABLE)，第一次顯示時為 null */
    val error: OpenFxHttpSender.PinCheck? = null,
    val verifying: Boolean = false
)

@Singleton
class TransferManager @Inject constructor(
    private val settings: SettingsRepository
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var receiver: OpenFxHttpReceiver? = null
    private var advertiser: Closeable? = null
    private val uploads = ConcurrentHashMap<String, OpenFxHttpSender.UploadHandle>()

    /** 傳送端：同一時間只處理一個配對流程 (多個檔案同時送往同一台時只問一次) */
    private val pairingLock = Mutex()
    private var pendingPin: CompletableDeferred<String?>? = null

    private val _pinRequest = MutableStateFlow<PinRequest?>(null)
    val pinRequest: StateFlow<PinRequest?> = _pinRequest.asStateFlow()

    /** 接收中使用的配對碼 (未接收時為 null)，給接收畫面與通知顯示 */
    private val _receivePin = MutableStateFlow<String?>(null)
    val receivePin: StateFlow<String?> = _receivePin.asStateFlow()

    /** 接收中時給畫面產生 QR Code 用；沒在接收或抓不到 IP 時為 null */
    private val _pairingCode = MutableStateFlow<PairingCode?>(null)
    val pairingCode: StateFlow<PairingCode?> = _pairingCode.asStateFlow()

    private class PairingCancelledException : IOException("Pairing code not entered")

    private class PeerUnreachableException(val deviceName: String) :
        IOException("Cannot reach $deviceName")

    private val _isReceiving = MutableStateFlow(false)
    val isReceiving: StateFlow<Boolean> = _isReceiving.asStateFlow()

    private val _receivedCount = MutableStateFlow(0)
    val receivedCount: StateFlow<Int> = _receivedCount.asStateFlow()

    private val _receiveDir = MutableStateFlow<String?>(null)
    val receiveDir: StateFlow<String?> = _receiveDir.asStateFlow()

    /** 最近完成接收的實際檔案路徑，供 UI 顯示後續操作（例如 APK 安裝）。 */
    private val _lastReceivedFilePath = MutableStateFlow<String?>(null)
    val lastReceivedFilePath: StateFlow<String?> = _lastReceivedFilePath.asStateFlow()

    private val _pendingAppInstalls = MutableStateFlow<List<String>>(emptyList())
    val pendingAppInstalls = _pendingAppInstalls.asStateFlow()

    fun consumeReceivedApp(path: String) {
        _pendingAppInstalls.update { it.filterNot { pending -> pending == path } }
    }

    private val _activeTasks = MutableStateFlow<List<TransferTask>>(emptyList())
    val activeTasks: StateFlow<List<TransferTask>> = _activeTasks.asStateFlow()

    private val _history = MutableStateFlow<List<TransferTask>>(emptyList())
    val history: StateFlow<List<TransferTask>> = _history.asStateFlow()

    /** 最近一次事件 (接收完成、錯誤等)，UI 狀態列依語系組成文字 */
    private val _lastEvent = MutableStateFlow<TransferEvent?>(null)
    val lastEvent: StateFlow<TransferEvent?> = _lastEvent.asStateFlow()

    // ===== 接收 =====

    @Synchronized
    fun startReceiver(
        deviceName: String,
        saveDir: File,
        pin: String,
        deviceId: String,
        port: Int = OpenFxHttpSender.DEFAULT_PORT
    ): Result<Unit> {
        if (receiver?.isRunning == true) return Result.success(Unit)

        return runCatching {
            saveDir.mkdirs()
            val instance = OpenFxHttpReceiver(
                deviceName = deviceName,
                saveDir = saveDir,
                pin = pin,
                deviceId = deviceId,
                port = port,
                onFileReceived = { file, senderDeviceName ->
                    _receivedCount.update { it + 1 }
                    _lastReceivedFilePath.value = file.absolutePath
                    if (file.extension.lowercase() in setOf("apk", "apks")) {
                        _pendingAppInstalls.update { it + file.absolutePath }
                    }
                    _lastEvent.value = TransferEvent.Received(file.name, senderDeviceName)
                },
                onError = { message ->
                    _lastEvent.value = TransferEvent.ReceiveError(message)
                },
                onProgress = { fileName, received, total, senderDeviceName ->
                    updateReceiveProgress(fileName, received, total, senderDeviceName)
                },
                onFailed = { fileName, reason ->
                    markReceiveFailed(fileName, reason)
                }
            )
            instance.start()
            check(instance.isRunning) { "Receiver failed to start (port $port in use?)" }

            advertiser = UdpDiscovery.startAdvertiser(deviceName, port, deviceId)
            receiver = instance
            _receivePin.value = pin
            _pairingCode.value = LanDeviceScanner.getLocalIpAddress()?.let { ip ->
                PairingCode(ip, port, pin, deviceId, deviceName)
            }
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
        _receivePin.value = null
        _pairingCode.value = null
        _isReceiving.value = false
    }

    /** 接收中更換配對碼 — 已記住舊碼的傳送端下次會被要求重新輸入 */
    @Synchronized
    fun updateReceivePin(pin: String) {
        val active = receiver ?: return
        active.pin = pin
        _receivePin.value = pin
        _pairingCode.update { it?.copy(pin = pin) }
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
        _lastEvent.value = if (reason == OpenFxHttpReceiver.REASON_NO_SPACE) {
            TransferEvent.NotEnoughSpace(fileName)
        } else {
            TransferEvent.ReceiveFailed(fileName, reason)
        }
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
                startUpload(file.name, file.length(), device) { handle, taskId, pin ->
                    OpenFxHttpSender.uploadFile(
                        targetIp = device.ip,
                        port = device.port,
                        file = file,
                        senderDeviceName = senderDeviceName,
                        pin = pin,
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
        onFinished: () -> Unit = {},
        openStream: () -> InputStream
    ): String {
        return startUpload(fileName, contentLength, device, onFinished) { handle, taskId, pin ->
            openStream().use { input ->
                OpenFxHttpSender.uploadStream(
                    targetIp = device.ip,
                    port = device.port,
                    fileName = fileName,
                    contentLength = contentLength,
                    input = input,
                    senderDeviceName = senderDeviceName,
                    pin = pin,
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
        onFinished: () -> Unit = {},
        upload: (OpenFxHttpSender.UploadHandle, String, String?) -> Boolean
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
                val pin = resolvePin(device)
                if (handle.isCancelled) return@launch
                val ok = upload(handle, taskId, pin)
                finishSend(taskId, ok, size)
                if (!ok) _lastEvent.value = TransferEvent.SendFailed(fileName)
            } catch (_: OpenFxHttpSender.UploadCancelledException) {
                // cancelTask 已標記 CANCELLED 並移入歷史
            } catch (_: PairingCancelledException) {
                if (!handle.isCancelled) {
                    updateTask(taskId) {
                        it.copy(status = TransferStatus.CANCELLED, endTime = System.currentTimeMillis())
                    }
                    moveToHistory(taskId)
                    _lastEvent.value = TransferEvent.SendCancelledNoPin(fileName)
                }
            } catch (_: OpenFxHttpSender.PinRejectedException) {
                // 預檢通過後對方才更換配對碼 — 清掉記住的碼，下次重新詢問
                settings.setPeerPin(device.peerKey, null)
                updateTask(taskId) {
                    it.copy(status = TransferStatus.FAILED, endTime = System.currentTimeMillis())
                }
                moveToHistory(taskId)
                _lastEvent.value = TransferEvent.SendPinExpired(fileName)
            } catch (e: Exception) {
                if (!handle.isCancelled) {
                    updateTask(taskId) {
                        it.copy(status = TransferStatus.FAILED, endTime = System.currentTimeMillis())
                    }
                    moveToHistory(taskId)
                    _lastEvent.value = if (e is PeerUnreachableException) {
                        TransferEvent.SendUnreachable(fileName, e.deviceName)
                    } else {
                        TransferEvent.SendFailed(fileName, e.message)
                    }
                }
            } finally {
                uploads.remove(taskId)
                onFinished()
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

    // ===== 配對 =====

    /**
     * 取得送往 [device] 要用的配對碼。
     * 已記住且有效 → 直接使用；舊版接收端 → null (不需配對碼)；
     * 否則透過 [pinRequest] 請使用者輸入，取消或逾時丟 [PairingCancelledException]。
     */
    private suspend fun resolvePin(device: OpenFxDevice): String? = pairingLock.withLock {
        val saved = settings.peerPin(device.peerKey)
        when (OpenFxHttpSender.checkPin(device.ip, device.port, saved)) {
            OpenFxHttpSender.PinCheck.OK -> return@withLock saved
            OpenFxHttpSender.PinCheck.LEGACY -> return@withLock null
            OpenFxHttpSender.PinCheck.UNREACHABLE -> throw PeerUnreachableException(device.deviceName)
            OpenFxHttpSender.PinCheck.WRONG,
            OpenFxHttpSender.PinCheck.LOCKED -> {
                if (saved != null) settings.setPeerPin(device.peerKey, null)
            }
        }

        val deferred = CompletableDeferred<String?>()
        pendingPin = deferred
        _pinRequest.value = PinRequest(device)
        try {
            withTimeoutOrNull(PIN_PROMPT_TIMEOUT_MS) { deferred.await() }
                ?: throw PairingCancelledException()
        } finally {
            pendingPin = null
            _pinRequest.value = null
        }
    }

    /** UI 送出配對碼 — 驗證成功才記住並繼續傳送，失敗則更新 [pinRequest] 的錯誤狀態 */
    fun submitPin(pin: String) {
        val request = _pinRequest.value ?: return
        if (request.verifying) return
        _pinRequest.value = request.copy(verifying = true, error = null)
        scope.launch {
            val device = request.device
            when (val result = OpenFxHttpSender.checkPin(device.ip, device.port, pin)) {
                OpenFxHttpSender.PinCheck.OK, OpenFxHttpSender.PinCheck.LEGACY -> {
                    settings.setPeerPin(device.peerKey, pin)
                    pendingPin?.complete(pin)
                }
                else -> _pinRequest.update { it?.copy(verifying = false, error = result) }
            }
        }
    }

    fun cancelPinRequest() {
        pendingPin?.complete(null)
    }

    sealed interface PairResult {
        data class Paired(val device: OpenFxDevice) : PairResult
        /** 該 IP 上沒有 OpenFX 接收端 (未開接收模式、不同網段) */
        data object NotFound : PairResult
        /** QR Code 裡的配對碼已失效 (接收端換過碼) */
        data object WrongPin : PairResult
        data object Locked : PairResult
        /** IP 上是另一台裝置 (DHCP 換過位址) */
        data object OtherDevice : PairResult
    }

    /** 掃描 QR Code 後：確認接收端、驗證配對碼，成功就記住裝置與配對碼 */
    suspend fun pairWithCode(code: PairingCode): PairResult = withContext(Dispatchers.IO) {
        val device = LanDeviceScanner.probe(code.host, code.port) ?: return@withContext PairResult.NotFound
        if (code.deviceId != null && device.deviceId != null && code.deviceId != device.deviceId) {
            return@withContext PairResult.OtherDevice
        }
        when (OpenFxHttpSender.checkPin(device.ip, device.port, code.pin)) {
            OpenFxHttpSender.PinCheck.OK, OpenFxHttpSender.PinCheck.LEGACY -> {
                settings.setPeerPin(device.peerKey, code.pin)
                settings.addManualDevice("${device.ip}:${device.port}")
                PairResult.Paired(device)
            }
            OpenFxHttpSender.PinCheck.WRONG -> PairResult.WrongPin
            OpenFxHttpSender.PinCheck.LOCKED -> PairResult.Locked
            OpenFxHttpSender.PinCheck.UNREACHABLE -> PairResult.NotFound
        }
    }

    // ===== 裝置發現 =====

    /** UDP 廣播掃描，最多等 timeoutMs；阻塞時間內在 IO dispatcher 執行 */
    suspend fun discoverDevices(timeoutMs: Int = 2000): List<OpenFxDevice> =
        withContext(Dispatchers.IO) {
            val found = UdpDiscovery.scan(timeoutMs)
            // 路由器擋廣播時，UDP 找不到手動加入的裝置 → 直接探測它們
            val manual = settings.manualDevices.first()
                .mapNotNull(::parseAddress)
                .filter { (ip, _) -> found.none { it.ip == ip } }
                .map { (ip, port) -> async { LanDeviceScanner.probe(ip, port, 1500) } }
                .awaitAll()
                .filterNotNull()
            found + manual
        }

    /** 以 IP 手動加入接收端；找不到回傳 null */
    suspend fun addManualDevice(ip: String, port: Int = OpenFxHttpSender.DEFAULT_PORT): OpenFxDevice? =
        withContext(Dispatchers.IO) {
            LanDeviceScanner.probe(ip, port)?.also { settings.addManualDevice("$ip:$port") }
        }

    private fun parseAddress(address: String): Pair<String, Int>? {
        val ip = address.substringBefore(':').takeIf { it.isNotBlank() } ?: return null
        val port = address.substringAfter(':', "").toIntOrNull() ?: OpenFxHttpSender.DEFAULT_PORT
        return ip to port
    }

    // ===== Private =====

    private companion object {
        const val PIN_PROMPT_TIMEOUT_MS = 5 * 60_000L
    }

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
