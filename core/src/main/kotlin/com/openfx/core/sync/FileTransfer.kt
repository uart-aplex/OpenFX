package com.openfx.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * 傳輸任務
 */
@Serializable
data class TransferTask(
    val id: String,
    val fileName: String,
    val fileSize: Long,
    val direction: TransferDirection,
    val targetDevice: String,
    val status: TransferStatus = TransferStatus.PENDING,
    val progress: Float = 0f,
    val bytesTransferred: Long = 0,
    val startTime: Long = 0,
    val endTime: Long = 0
) {
    val speedBytesPerSec: Long
        get() {
            if (startTime == 0L || bytesTransferred == 0L) return 0
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            return if (elapsed > 0) (bytesTransferred / elapsed).toLong() else 0
        }

    val formattedSpeed: String
        get() {
            val speed = speedBytesPerSec
            return when {
                speed < 1024 -> "$speed B/s"
                speed < 1024 * 1024 -> "${"%.1f".format(speed / 1024.0)} KB/s"
                else -> "${"%.1f".format(speed / (1024.0 * 1024))} MB/s"
            }
        }
}

enum class TransferDirection { SEND, RECEIVE }

enum class TransferStatus {
    PENDING, ACTIVE, PAUSED, COMPLETED, FAILED, CANCELLED
}

/**
 * P2P 檔案傳輸引擎
 * 使用 Wi-Fi Direct 進行高速直連傳輸
 */
interface FileTransferEngine {

    /** 目前所有傳輸任務 */
    val activeTasks: StateFlow<List<TransferTask>>

    /** 傳輸歷史 */
    val transferHistory: StateFlow<List<TransferTask>>

    /**
     * 傳送檔案到目標裝置
     * @param filePaths 要傳送的檔案路徑
     * @param targetDevice 目標裝置
     * @param targetPath 目標路徑 (在對方裝置上的儲存位置)
     * @return 傳輸任務 ID
     */
    suspend fun sendFiles(
        filePaths: List<String>,
        targetDevice: DeviceInfo,
        targetPath: String = "/sdcard/OpenFX/Received/"
    ): Result<List<String>>

    /**
     * 接收檔案 (啟動接收服務)
     * @param savePath 儲存路徑
     */
    suspend fun startReceiveServer(savePath: String = "/sdcard/OpenFX/Received/"): Result<Unit>

    /** 停止接收服務 */
    fun stopReceiveServer()

    /** 暫停傳輸 */
    suspend fun pauseTask(taskId: String): Result<Unit>

    /** 恢復傳輸 */
    suspend fun resumeTask(taskId: String): Result<Unit>

    /** 取消傳輸 */
    suspend fun cancelTask(taskId: String): Result<Unit>

    /** 清除已完成的傳輸記錄 */
    fun clearCompleted()
}
