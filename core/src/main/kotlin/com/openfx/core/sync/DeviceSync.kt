package com.openfx.core.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/**
 * 裝置資訊
 */
@Serializable
data class DeviceInfo(
    val id: String,
    val name: String,
    val type: DeviceType,
    val ipAddress: String,
    val port: Int = DEFAULT_PORT,
    val isPaired: Boolean = false,
    val isOnline: Boolean = false,
    val lastSeen: Long = 0
) {
    companion object {
        const val DEFAULT_PORT = 18920
    }
}

enum class DeviceType {
    PHONE, TABLET, TV, UNKNOWN
}

/**
 * 配對方式
 */
sealed class PairingMethod {
    data class QrCode(val payload: String) : PairingMethod()
    data class Pin(val code: String) : PairingMethod()
}

/**
 * 配對狀態
 */
sealed class PairingState {
    data object Idle : PairingState()
    data object WaitingForPeer : PairingState()
    data class Connecting(val device: DeviceInfo) : PairingState()
    data class Connected(val device: DeviceInfo) : PairingState()
    data class Failed(val reason: String) : PairingState()
}

/**
 * 裝置發現與配對介面
 */
interface DeviceDiscovery {
    /** 附近可見的裝置清單 (即時更新) */
    val nearbyDevices: StateFlow<List<DeviceInfo>>

    /** 已配對的裝置清單 */
    val pairedDevices: StateFlow<List<DeviceInfo>>

    /** 目前的配對狀態 */
    val pairingState: StateFlow<PairingState>

    /** 開始掃描附近裝置 (mDNS + UDP broadcast) */
    suspend fun startDiscovery()

    /** 停止掃描 */
    fun stopDiscovery()

    /** 產生配對資訊 (QR Code 或 PIN) */
    suspend fun generatePairingInfo(): PairingMethod

    /** 使用 QR Code payload 或 PIN 碼配對指定裝置 */
    suspend fun pairDevice(device: DeviceInfo, method: PairingMethod): Result<DeviceInfo>

    /** 解除配對 */
    suspend fun unpairDevice(deviceId: String): Result<Unit>

    /** 檢查已配對裝置的在線狀態 */
    suspend fun refreshPairedDeviceStatus()
}

/**
 * 遠端控制指令 — 手機發送到 TV 執行
 */
@Serializable
sealed class RemoteCommand {
    @Serializable
    data class ListDir(val path: String) : RemoteCommand()

    @Serializable
    data class Rename(val path: String, val newName: String) : RemoteCommand()

    @Serializable
    data class Delete(val paths: List<String>) : RemoteCommand()

    @Serializable
    data class Move(val sources: List<String>, val dest: String) : RemoteCommand()

    @Serializable
    data class Copy(val sources: List<String>, val dest: String) : RemoteCommand()

    @Serializable
    data class CreateDir(val parentPath: String, val name: String) : RemoteCommand()

    @Serializable
    data class PlayMedia(val path: String) : RemoteCommand()

    @Serializable
    data class GetStorageInfo(val dummy: Boolean = true) : RemoteCommand()
}

/**
 * 遠端控制回應
 */
@Serializable
sealed class RemoteResponse {
    @Serializable
    data class FileList(val files: List<com.openfx.core.file.FileItem>) : RemoteResponse()

    @Serializable
    data class Success(val message: String = "OK") : RemoteResponse()

    @Serializable
    data class Error(val message: String) : RemoteResponse()
}

/**
 * 遠端檔案操作 — 透過 TCP 連線操控配對裝置
 */
interface RemoteFileControl {
    /** 連線到指定裝置 */
    suspend fun connect(device: DeviceInfo): Result<Unit>

    /** 斷線 */
    fun disconnect()

    /** 發送指令並等待回應 */
    suspend fun sendCommand(command: RemoteCommand): Result<RemoteResponse>

    /** 連線狀態 */
    val connectionState: StateFlow<ConnectionState>
}

enum class ConnectionState {
    DISCONNECTED, CONNECTING, CONNECTED, ERROR
}
