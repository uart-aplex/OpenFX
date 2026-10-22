package com.openfx.mobile.ui.transfer

import com.openfx.mobile.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.openfx.core.settings.SettingsRepository
import com.openfx.core.sync.TransferDirection
import com.openfx.core.sync.TransferManager
import com.openfx.core.sync.TransferStatus
import com.openfx.mobile.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 傳輸前景服務 — 持有 TransferManager 的接收端
 *
 * 接收模式開啟後，即使使用者離開 App 也能繼續收檔。
 * 通知有三種外觀：
 * - 待命中   → 「OpenFX 接收待命中 · 等待其他裝置傳檔…」
 * - 接收中   → 「正在接收：xxx.mp4」+ 即時進度條
 * - 接收完畢 → 回到待命外觀，並顯示本次累計接收數
 *
 * (前景服務運行期間通知不能移除 — Android 規定，所以只能改外觀)
 */
@AndroidEntryPoint
class TransferService : Service() {

    @Inject
    lateinit var transferManager: TransferManager

    @Inject
    lateinit var settings: SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 接收期間持有 — 避免螢幕關閉後 WiFi 降速導致傳輸停滯 */
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * 通知更新佇列 (CONFLATED = 只保留最新一則)
     *
     * Android 系統對通知更新有速率限制 — 傳輸進度在快速區網下
     * 每秒可產生數十則更新，直接 notify() 會被系統丟棄，
     * 導致最後的「完成/待命」狀態永遠送不到 (通知卡在半路百分比)。
     * 這裡節流為每 600ms 最多一則，且保證最終狀態一定送出。
     */
    private val notificationQueue = Channel<NotificationUi>(Channel.CONFLATED)

    companion object {
        const val ACTION_START_RECEIVE = "com.openfx.action.START_RECEIVE"
        const val ACTION_STOP_RECEIVE = "com.openfx.action.STOP_RECEIVE"
        private const val CHANNEL_ID = "openfx_transfer"
        private const val NOTIFICATION_ID = 42
    }

    /** 通知要顯示的內容 (distinctUntilChanged 用,避免每 64KB 都重發通知) */
    private data class NotificationUi(
        val isTransferring: Boolean,
        val title: String,
        val text: String,
        val progressPercent: Int?,
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_RECEIVE -> startReceiving()
            ACTION_STOP_RECEIVE -> stopReceiving()
        }
        return START_STICKY
    }

    private fun startReceiving() {
        startForegroundWithNotification(
            NotificationUi(
                isTransferring = false,
                title = getString(R.string.notify_receive_standby),
                text = getString(R.string.notify_waiting),
                progressPercent = null
            )
        )

        val saveDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "OpenFX"
        )

        // 節流的通知發送器 — 馬上發一則，之後每 600ms 最多一則，永遠發最新狀態
        serviceScope.launch {
            for (ui in notificationQueue) {
                updateNotification(ui)
                delay(600)
            }
        }

        // 用設定頁的裝置名稱啟動接收端 (其他裝置掃描時看到的名字)
        serviceScope.launch {
            val deviceName = settings.deviceName.first()
            transferManager.startReceiver(
                deviceName = deviceName,
                saveDir = saveDir,
                pin = settings.receivePinOrCreate(),
                deviceId = settings.deviceId()
            ).onFailure {
                stopSelf()
                return@launch
            }
            acquireWifiLock()
        }

        // 接收狀態即時反映到通知：有進行中的接收任務 → 顯示進度；沒有 → 待命外觀
        serviceScope.launch {
            combine(
                transferManager.activeTasks,
                transferManager.receivedCount,
                transferManager.lastEvent,
                settings.transferCompleteNotify,
                transferManager.receivePin
            ) { tasks, count, event, notifyEnabled, pin ->
                val receiving = tasks.firstOrNull {
                    it.direction == TransferDirection.RECEIVE &&
                        it.status == TransferStatus.ACTIVE
                }

                if (receiving != null) {
                    val percent = (receiving.progress * 100).toInt().coerceIn(0, 100)
                    NotificationUi(
                        isTransferring = true,
                        title = getString(R.string.notify_receiving, receiving.fileName),
                        text = "$percent%",
                        progressPercent = percent
                    )
                } else {
                    val text = when {
                        // 「傳輸完成通知」關閉時只顯示待命文字
                        !notifyEnabled -> getString(R.string.notify_waiting)
                        count > 0 && event != null -> resources.getQuantityString(
                            R.plurals.notify_received_count_event, count, count, event.message(resources)
                        )
                        count > 0 -> resources.getQuantityString(
                            R.plurals.notify_received_count, count, count
                        )
                        else -> getString(R.string.notify_waiting)
                    }
                    NotificationUi(
                        isTransferring = false,
                        title = pin?.let { getString(R.string.notify_receive_standby_pin, it) }
                            ?: getString(R.string.notify_receive_standby),
                        text = text,
                        progressPercent = null
                    )
                }
            }
                .distinctUntilChanged()
                .collect { ui -> notificationQueue.trySend(ui) }
        }
    }

    private fun stopReceiving() {
        transferManager.stopReceiver()
        releaseWifiLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        transferManager.stopReceiver()
        releaseWifiLock()
        super.onDestroy()
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wifiLock = wifiManager.createWifiLock(mode, "openfx:transfer").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    private fun startForegroundWithNotification(ui: NotificationUi) {
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(ui),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun updateNotification(ui: NotificationUi) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(ui))
    }

    private fun buildNotification(ui: NotificationUi): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopAction = PendingIntent.getService(
            this,
            1,
            Intent(this, TransferService::class.java).setAction(ACTION_STOP_RECEIVE),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (ui.isTransferring) android.R.drawable.stat_sys_download
                else android.R.drawable.stat_sys_download_done
            )
            .setContentTitle(ui.title)
            .setContentText(ui.text)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notify_stop_receive), stopAction)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .apply {
                ui.progressPercent?.let { setProgress(100, it, false) }
            }
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notify_channel_transfer),
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }
}
