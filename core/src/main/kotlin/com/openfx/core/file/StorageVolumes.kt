package com.openfx.core.file

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.openfx.core.R
import java.io.File

/**
 * 列出目前已掛載的儲存空間 (內部儲存、SD 卡、USB 隨身碟)
 *
 * 用 StorageManager 而不是 getExternalFilesDirs：後者只在 App 曾經建立過私有目錄的磁區才會出現，
 * 而且拿不到系統給的名稱 (例如「SanDisk USB 隨身碟」)。
 */
object StorageVolumes {

    fun list(context: Context): List<StorageVolume> {
        val internalPath = Environment.getExternalStorageDirectory().absolutePath
        val result = mutableListOf(volume(context.getString(R.string.core_storage_internal), File(internalPath), false, false))

        val manager = context.getSystemService(StorageManager::class.java) ?: return result
        manager.storageVolumes
            .filter { it.isRemovable }
            .filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .forEach { platformVolume ->
                val dir = directoryOf(platformVolume) ?: return@forEach
                if (dir.absolutePath == internalPath || !dir.canRead()) return@forEach
                val description = platformVolume.getDescription(context).orEmpty()
                // 系統沒有公開「是不是 USB」的 API；名稱含 USB 就當 USB，其餘可移除磁區視為 SD 卡
                val isUsb = description.contains("USB", ignoreCase = true)
                val name = description.ifBlank {
                    context.getString(if (isUsb) R.string.core_storage_usb else R.string.core_storage_sd_card)
                }
                result += volume(name, dir, removable = true, usb = isUsb)
            }
        return result
    }

    /** 插入/拔除/退出磁區時發出一次；收集期間才註冊 receiver */
    fun changes(context: Context): Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            // 這些廣播只帶 file:// data，沒有 scheme 的 filter 收不到
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        awaitClose { context.unregisterReceiver(receiver) }
    }

    /** 第一個可移除的磁區 (TV「USB 裝置」入口用)；沒有回傳 null */
    fun firstRemovable(context: Context): StorageVolume? = list(context).firstOrNull { it.isRemovable }

    private fun volume(name: String, dir: File, removable: Boolean, usb: Boolean): StorageVolume {
        val stat = runCatching { StatFs(dir.path) }.getOrNull()
        val total = stat?.totalBytes ?: 0L
        return StorageVolume(
            name = name,
            path = dir.absolutePath,
            totalBytes = total,
            usedBytes = total - (stat?.availableBytes ?: 0L),
            isRemovable = removable,
            isUsb = usb
        )
    }

    private fun directoryOf(volume: android.os.storage.StorageVolume): File? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) return volume.directory
        // API 24–29 沒有公開的路徑 API，用隱藏的 getPathFile()/getPath()
        return runCatching {
            volume.javaClass.getMethod("getPathFile").invoke(volume) as? File
        }.getOrNull() ?: runCatching {
            (volume.javaClass.getMethod("getPath").invoke(volume) as? String)?.let(::File)
        }.getOrNull()
    }
}
