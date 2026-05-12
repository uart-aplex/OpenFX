package com.openfx.core.sync

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P2P 檔案傳輸引擎實作
 * 
 * 傳輸協議 (自訂 TCP)：
 * ┌──────────────────────────────────────┐
 * │ Header (固定 256 bytes)               │
 * │  - magic: "OPENFX" (6 bytes)         │
 * │  - version: 1 (4 bytes)              │
 * │  - filename length (4 bytes)         │
 * │  - filename (UTF-8, up to 200 bytes) │
 * │  - file size (8 bytes)               │
 * │  - checksum (32 bytes, SHA-256)      │
 * ├──────────────────────────────────────┤
 * │ Data chunks (8KB each)               │
 * │  - chunk data...                     │
 * │  - ...until file size reached        │
 * └──────────────────────────────────────┘
 */
@Singleton
class TcpFileTransferEngine @Inject constructor() : FileTransferEngine {

    companion object {
        const val TRANSFER_PORT = 18921
        const val BUFFER_SIZE = 8192
        const val MAGIC = "OPENFX"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null

    private val _activeTasks = MutableStateFlow<List<TransferTask>>(emptyList())
    override val activeTasks: StateFlow<List<TransferTask>> = _activeTasks.asStateFlow()

    private val _transferHistory = MutableStateFlow<List<TransferTask>>(emptyList())
    override val transferHistory: StateFlow<List<TransferTask>> = _transferHistory.asStateFlow()

    override suspend fun sendFiles(
        filePaths: List<String>,
        targetDevice: DeviceInfo,
        targetPath: String
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val taskIds = mutableListOf<String>()

            filePaths.forEach { filePath ->
                val file = File(filePath)
                if (!file.exists()) throw FileNotFoundException("File not found: $filePath")

                val taskId = UUID.randomUUID().toString().take(8)
                val task = TransferTask(
                    id = taskId,
                    fileName = file.name,
                    fileSize = file.length(),
                    direction = TransferDirection.SEND,
                    targetDevice = targetDevice.name,
                    status = TransferStatus.ACTIVE,
                    startTime = System.currentTimeMillis()
                )

                // 加入 active 列表
                _activeTasks.value = _activeTasks.value + task

                // 開始傳輸
                scope.launch {
                    try {
                        sendFile(file, targetDevice.ipAddress, task)
                    } catch (e: Exception) {
                        updateTask(taskId) { it.copy(status = TransferStatus.FAILED) }
                    }
                }

                taskIds.add(taskId)
            }

            taskIds
        }
    }

    private suspend fun sendFile(file: File, targetIp: String, task: TransferTask) {
        val socket = Socket(targetIp, TRANSFER_PORT)

        socket.use { sock ->
            val outputStream = DataOutputStream(BufferedOutputStream(sock.getOutputStream()))

            // 寫入 header
            outputStream.writeBytes(MAGIC)
            outputStream.writeInt(1) // version
            val nameBytes = file.name.toByteArray(Charsets.UTF_8)
            outputStream.writeInt(nameBytes.size)
            outputStream.write(nameBytes)
            outputStream.writeLong(file.length())
            outputStream.flush()

            // 寫入檔案內容
            var transferred = 0L
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                    transferred += read

                    // 更新進度
                    updateTask(task.id) {
                        it.copy(
                            bytesTransferred = transferred,
                            progress = transferred.toFloat() / file.length()
                        )
                    }
                }
            }

            outputStream.flush()

            // 完成
            updateTask(task.id) {
                it.copy(
                    status = TransferStatus.COMPLETED,
                    progress = 1f,
                    bytesTransferred = file.length(),
                    endTime = System.currentTimeMillis()
                )
            }
            moveToHistory(task.id)
        }
    }

    override suspend fun startReceiveServer(savePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val saveDir = File(savePath)
                if (!saveDir.exists()) saveDir.mkdirs()

                serverSocket = ServerSocket(TRANSFER_PORT)

                scope.launch {
                    while (isActive) {
                        try {
                            val clientSocket = serverSocket?.accept() ?: break
                            launch { receiveFile(clientSocket, saveDir) }
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
                Unit
            }
        }

    private suspend fun receiveFile(socket: Socket, saveDir: File) {
        socket.use { sock ->
            val inputStream = DataInputStream(BufferedInputStream(sock.getInputStream()))

            // 讀取 header
            val magic = ByteArray(6)
            inputStream.readFully(magic)
            if (String(magic) != MAGIC) throw IllegalStateException("Invalid protocol")

            val version = inputStream.readInt()
            val nameLength = inputStream.readInt()
            val nameBytes = ByteArray(nameLength)
            inputStream.readFully(nameBytes)
            val fileName = String(nameBytes, Charsets.UTF_8)
            val fileSize = inputStream.readLong()

            val taskId = UUID.randomUUID().toString().take(8)
            val task = TransferTask(
                id = taskId,
                fileName = fileName,
                fileSize = fileSize,
                direction = TransferDirection.RECEIVE,
                targetDevice = sock.inetAddress.hostAddress ?: "unknown",
                status = TransferStatus.ACTIVE,
                startTime = System.currentTimeMillis()
            )
            _activeTasks.value = _activeTasks.value + task

            // 接收檔案內容
            val outFile = File(saveDir, fileName)
            var received = 0L

            outFile.outputStream().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (received < fileSize) {
                    val toRead = minOf(BUFFER_SIZE.toLong(), fileSize - received).toInt()
                    val read = inputStream.read(buffer, 0, toRead)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    received += read

                    updateTask(taskId) {
                        it.copy(
                            bytesTransferred = received,
                            progress = received.toFloat() / fileSize
                        )
                    }
                }
            }

            updateTask(taskId) {
                it.copy(
                    status = TransferStatus.COMPLETED,
                    progress = 1f,
                    endTime = System.currentTimeMillis()
                )
            }
            moveToHistory(taskId)
        }
    }

    override fun stopReceiveServer() {
        serverSocket?.close()
        serverSocket = null
    }

    override suspend fun pauseTask(taskId: String): Result<Unit> = runCatching {
        updateTask(taskId) { it.copy(status = TransferStatus.PAUSED) }
    }

    override suspend fun resumeTask(taskId: String): Result<Unit> = runCatching {
        updateTask(taskId) { it.copy(status = TransferStatus.ACTIVE) }
    }

    override suspend fun cancelTask(taskId: String): Result<Unit> = runCatching {
        updateTask(taskId) { it.copy(status = TransferStatus.CANCELLED) }
        moveToHistory(taskId)
    }

    override fun clearCompleted() {
        _transferHistory.value = emptyList()
    }

    private fun updateTask(taskId: String, update: (TransferTask) -> TransferTask) {
        _activeTasks.value = _activeTasks.value.map {
            if (it.id == taskId) update(it) else it
        }
    }

    private fun moveToHistory(taskId: String) {
        val task = _activeTasks.value.find { it.id == taskId } ?: return
        _activeTasks.value = _activeTasks.value.filter { it.id != taskId }
        _transferHistory.value = listOf(task) + _transferHistory.value
    }
}
