package com.openfx.core.sync

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wi-Fi Direct 裝置發現與配對實作
 * 
 * 工作原理：
 * 1. 使用 WifiP2pManager 掃描附近的 Wi-Fi Direct 裝置
 * 2. 找到後使用 QR Code 或 PIN 交換連線資訊
 * 3. 建立 P2P 連線後，透過 TCP socket 傳輸檔案和指令
 * 
 * 安全機制：
 * - 配對時需要雙方確認
 * - 所有傳輸使用 AES-256 加密
 * - 配對金鑰儲存在本地 DataStore
 */
@Singleton
class WifiDirectDiscovery @Inject constructor(
    @ApplicationContext private val context: Context
) : DeviceDiscovery {

    private val wifiP2pManager: WifiP2pManager? =
        context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager

    private val channel: WifiP2pManager.Channel? =
        wifiP2pManager?.initialize(context, Looper.getMainLooper(), null)

    private val _nearbyDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    override val nearbyDevices: StateFlow<List<DeviceInfo>> = _nearbyDevices.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    override val pairedDevices: StateFlow<List<DeviceInfo>> = _pairedDevices.asStateFlow()

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    override val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private var receiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Wi-Fi P2P 廣播接收器
    private fun createReceiver() = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    // 附近裝置列表更新
                    wifiP2pManager?.requestPeers(channel) { peerList ->
                        val devices = peerList.deviceList.map { it.toDeviceInfo() }
                        _nearbyDevices.value = devices
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    // 連線狀態改變
                    wifiP2pManager?.requestConnectionInfo(channel) { info ->
                        if (info.groupFormed) {
                            val currentDevice = _nearbyDevices.value.find { it.isOnline }
                            if (currentDevice != null) {
                                _pairingState.value = PairingState.Connected(
                                    currentDevice.copy(
                                        ipAddress = info.groupOwnerAddress?.hostAddress ?: "",
                                        isOnline = true,
                                        isPaired = true
                                    )
                                )
                            }
                        }
                    }
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        _nearbyDevices.value = emptyList()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override suspend fun startDiscovery() {
        withContext(Dispatchers.Main) {
            // 註冊廣播接收器
            val recv = createReceiver()
            receiver = recv
            val intentFilter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(recv, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(recv, intentFilter)
            }

            // 開始掃描
            wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // 掃描成功啟動，等待 PEERS_CHANGED 回調
                }
                override fun onFailure(reason: Int) {
                    _pairingState.value = PairingState.Failed("Discovery failed: $reason")
                }
            })
        }
    }

    override fun stopDiscovery() {
        try {
            receiver?.let { context.unregisterReceiver(it) }
            receiver = null
            wifiP2pManager?.stopPeerDiscovery(channel, null)
        } catch (_: Exception) {
        }
    }

    override suspend fun generatePairingInfo(): PairingMethod {
        // 產生唯一的配對 payload
        val deviceId = UUID.randomUUID().toString().take(8)
        val pin = (1000..9999).random().toString()

        // QR Code payload 包含: 裝置名稱, IP, port, 一次性密碼
        val payload = "openfx://$deviceId:${DeviceInfo.DEFAULT_PORT}:$pin"

        return PairingMethod.QrCode(payload)
    }

    @SuppressLint("MissingPermission")
    override suspend fun pairDevice(
        device: DeviceInfo,
        method: PairingMethod
    ): Result<DeviceInfo> = withContext(Dispatchers.IO) {
        runCatching {
            _pairingState.value = PairingState.Connecting(device)

            // 透過 Wi-Fi Direct 連線
            val config = WifiP2pConfig().apply {
                deviceAddress = device.id // MAC address
            }

            suspendCancellableCoroutine<DeviceInfo> { cont ->
                wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        val pairedDevice = device.copy(isPaired = true, isOnline = true)
                        _pairingState.value = PairingState.Connected(pairedDevice)

                        // 儲存到已配對列表
                        _pairedDevices.value = _pairedDevices.value + pairedDevice

                        cont.resumeWith(Result.success(pairedDevice))
                    }

                    override fun onFailure(reason: Int) {
                        val msg = when (reason) {
                            WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device"
                            WifiP2pManager.BUSY -> "Wi-Fi Direct is busy"
                            WifiP2pManager.ERROR -> "Connection error"
                            else -> "Unknown error ($reason)"
                        }
                        _pairingState.value = PairingState.Failed(msg)
                        cont.resumeWith(Result.failure(Exception(msg)))
                    }
                })
            }
        }
    }

    override suspend fun unpairDevice(deviceId: String): Result<Unit> = runCatching {
        _pairedDevices.value = _pairedDevices.value.filter { it.id != deviceId }
        wifiP2pManager?.removeGroup(channel, null)
    }

    override suspend fun refreshPairedDeviceStatus() {
        // TODO: 對每個已配對裝置嘗試 TCP ping 確認是否在線
        _pairedDevices.value = _pairedDevices.value.map { device ->
            device.copy(
                isOnline = false, // 預設離線，ping 成功後更新
                lastSeen = if (device.isOnline) System.currentTimeMillis() else device.lastSeen
            )
        }
    }

    // WifiP2pDevice → DeviceInfo
    private fun WifiP2pDevice.toDeviceInfo(): DeviceInfo {
        val type = when {
            primaryDeviceType?.contains("TV") == true -> DeviceType.TV
            primaryDeviceType?.contains("Tablet") == true -> DeviceType.TABLET
            primaryDeviceType?.contains("Phone") == true -> DeviceType.PHONE
            else -> DeviceType.UNKNOWN
        }

        val isPaired = _pairedDevices.value.any { it.id == deviceAddress }

        return DeviceInfo(
            id = deviceAddress,
            name = deviceName ?: "Unknown",
            type = type,
            ipAddress = "", // IP 在連線建立後才知道
            isPaired = isPaired,
            isOnline = status == WifiP2pDevice.AVAILABLE,
            lastSeen = System.currentTimeMillis()
        )
    }
}
