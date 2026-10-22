package com.openfx.mobile.ui.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openfx.core.vault.VaultCorruptedException
import com.openfx.core.vault.VaultEntry
import com.openfx.core.vault.VaultManager
import com.openfx.core.vault.VaultState
import com.openfx.core.vault.VaultWrongPasswordException
import com.openfx.mobile.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class VaultUiState(
    val folderId: String? = null,
    val path: List<VaultEntry> = emptyList(),
    val entries: List<VaultEntry> = emptyList(),
    /** 進行中的工作說明 (加密中、解密中…)；null = 閒置 */
    val busy: String? = null,
    val message: String? = null,
    val error: String? = null,
    /** 在 App 內檢視的圖片 (解密後只存在記憶體) */
    val preview: Bitmap? = null,
    val previewName: String = "",
    /** 要交給外部 App 開啟的解密暫存檔 */
    val openFile: File? = null
)

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val manager: VaultManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val vaultState: StateFlow<VaultState> = manager.state

    private val _ui = MutableStateFlow(VaultUiState())
    val ui: StateFlow<VaultUiState> = _ui.asStateFlow()

    private val store get() = manager.store

    /** 啟動時帶進來、等解鎖後要加密的本機檔案 */
    private var pendingImport: Pair<List<String>, Boolean>? = null

    // ===== 建立 / 解鎖 =====

    fun create(password: CharArray, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { manager.create(password) }
                .onSuccess { afterUnlock() }
                .onFailure { onError(describe(it)) }
        }
    }

    fun unlock(password: CharArray, onError: (String) -> Unit) {
        _ui.update { it.copy(busy = context.getString(R.string.vault_unlocking)) }
        viewModelScope.launch {
            runCatching { manager.unlock(password) }
                .onSuccess { afterUnlock() }
                .onFailure { e ->
                    _ui.update { it.copy(busy = null) }
                    onError(describe(e))
                }
        }
    }

    fun unlockWithKey(key: ByteArray, onError: (String) -> Unit) {
        viewModelScope.launch {
            runCatching { manager.unlockWithKey(key) }
                .onSuccess { afterUnlock() }
                .onFailure { onError(describe(it)) }
            key.fill(0)
        }
    }

    private suspend fun afterUnlock() {
        _ui.update { VaultUiState() }
        refresh()
        pendingImport?.let { (paths, deleteOriginals) ->
            pendingImport = null
            importPaths(paths, deleteOriginals)
        }
    }

    fun lock() {
        manager.lock()
        _ui.value = VaultUiState()
    }

    fun requestImportAfterUnlock(paths: List<String>, deleteOriginals: Boolean) {
        if (vaultState.value == VaultState.UNLOCKED) {
            importPaths(paths, deleteOriginals)
        } else {
            pendingImport = paths to deleteOriginals
        }
    }

    // ===== 瀏覽 =====

    fun openFolder(id: String?) {
        _ui.update { it.copy(folderId = id) }
        viewModelScope.launch { refresh() }
    }

    /** 回上一層；已在最上層回傳 false */
    fun up(): Boolean {
        val current = _ui.value.folderId ?: return false
        val parent = store.get(current)?.parentId
        openFolder(parent)
        return true
    }

    private suspend fun refresh() {
        val folder = _ui.value.folderId
        val (path, entries) = withContext(Dispatchers.IO) {
            runCatching { store.pathOf(folder) to store.list(folder) }.getOrDefault(emptyList<VaultEntry>() to emptyList())
        }
        _ui.update { it.copy(path = path, entries = entries) }
    }

    // ===== 新增 =====

    fun createFolder(name: String) = work(R.string.vault_working) {
        store.createFolder(_ui.value.folderId, name)
        null
    }

    /** 從系統檔案選擇器加入 (無法刪除原始檔，只加密複本) */
    fun importUris(uris: List<Uri>) = work(R.string.vault_encrypting) {
        var count = 0
        uris.forEach { uri ->
            val (name, modified) = describeUri(uri)
            context.contentResolver.openInputStream(uri)?.use { input ->
                store.addFile(_ui.value.folderId, name, modified, input)
                count++
            }
        }
        context.resources.getQuantityString(R.plurals.vault_imported, count, count)
    }

    /** 從 OpenFX 檔案瀏覽器加入本機檔案/資料夾；[deleteOriginals] 成功加密後刪除原檔 */
    fun importPaths(paths: List<String>, deleteOriginals: Boolean) = work(R.string.vault_encrypting) {
        var count = 0
        paths.map(::File).filter { it.exists() }.forEach { source ->
            store.addLocal(_ui.value.folderId, source)
            count++
            if (deleteOriginals) source.deleteRecursively()
        }
        context.resources.getQuantityString(
            if (deleteOriginals) R.plurals.vault_imported_moved else R.plurals.vault_imported, count, count
        )
    }

    /** 相機拍攝的暫存照片直接加密存入，然後刪除暫存檔 */
    fun importCapturedPhoto(temp: File) = work(R.string.vault_encrypting) {
        try {
            if (temp.length() == 0L) return@work null
            val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
            temp.inputStream().use { store.addFile(_ui.value.folderId, name, System.currentTimeMillis(), it) }
            context.resources.getQuantityString(R.plurals.vault_imported, 1, 1)
        } finally {
            temp.delete()
        }
    }

    /** 拍照用的暫存檔 (cache 底下，FileProvider 可分享給相機 App) */
    fun newCaptureFile(): File =
        File(manager.viewDir, "capture_${System.currentTimeMillis()}.jpg").also { it.parentFile?.mkdirs() }

    // ===== 檢視 / 匯出 =====

    fun view(entry: VaultEntry) {
        if (entry.isDirectory) {
            openFolder(entry.id)
            return
        }
        if (isImage(entry.name)) {
            work(R.string.vault_decrypting) {
                val bitmap = decodeImage(entry)
                if (bitmap != null) {
                    _ui.update { it.copy(preview = bitmap, previewName = entry.name) }
                    null
                } else {
                    openExternally(entry)
                    null
                }
            }
        } else {
            work(R.string.vault_decrypting) {
                openExternally(entry)
                null
            }
        }
    }

    private fun openExternally(entry: VaultEntry) {
        // 每個項目獨立資料夾，保留原始檔名讓外部 App 顯示正確
        val target = store.export(entry.id, File(manager.viewDir, entry.id).apply { deleteRecursively() })
        _ui.update { it.copy(openFile = target) }
    }

    fun consumeOpenFile() = _ui.update { it.copy(openFile = null) }

    fun closePreview() {
        _ui.value.preview?.recycle()
        _ui.update { it.copy(preview = null, previewName = "") }
    }

    /** 解密匯出到 Download/OpenFX/Vault (之後就是一般未加密檔案) */
    fun export(entry: VaultEntry) = work(R.string.vault_decrypting) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "OpenFX/Vault"
        )
        val file = store.export(entry.id, dir)
        context.getString(R.string.vault_exported, file.absolutePath)
    }

    fun rename(entry: VaultEntry, name: String) = work(R.string.vault_working) {
        store.rename(entry.id, name)
        null
    }

    fun delete(entry: VaultEntry) = work(R.string.vault_working) {
        store.delete(listOf(entry.id))
        null
    }

    fun changePassword(old: CharArray, new: CharArray, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val error = withContext(Dispatchers.IO) {
                runCatching { store.changePassword(old, new) }.exceptionOrNull()
            }
            old.fill('\u0000')
            new.fill('\u0000')
            onDone(error?.let(::describe))
        }
    }

    // ===== 備份 / 還原 =====

    /** 建議的備份檔名 */
    fun backupFileName(): String =
        "OpenFX-vault-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date()) + ".ofxvault"

    fun backupTo(uri: Uri) = work(R.string.vault_backing_up) {
        val output = context.contentResolver.openOutputStream(uri) ?: error("Cannot write backup")
        manager.backup(output)
        context.getString(R.string.vault_backup_done, describeUri(uri).first)
    }

    /** 沒有系統檔案選擇器時直接寫到 Download/OpenFX */
    fun backupToDownloads() = work(R.string.vault_backing_up) {
        val target = File(manager.fallbackBackupDir.apply { mkdirs() }, backupFileName())
        manager.backup(target.outputStream())
        context.getString(R.string.vault_backup_done, target.absolutePath)
    }

    fun restoreFrom(uri: Uri) = work(R.string.vault_restoring) {
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot read backup")
        manager.restore(input)
        context.getString(R.string.vault_restore_done)
    }

    /** 沒有系統檔案選擇器時，還原 Download/OpenFX 裡最新的 .ofxvault */
    fun restoreLatestFromDownloads() = work(R.string.vault_restoring) {
        val latest = manager.fallbackBackupDir.listFiles { file -> file.name.endsWith(".ofxvault") }
            ?.maxByOrNull { it.lastModified() }
            ?: return@work context.getString(R.string.vault_restore_none)
        manager.restore(latest.inputStream())
        context.getString(R.string.vault_restore_done)
    }

    fun clearMessage() = _ui.update { it.copy(message = null, error = null) }

    fun showMessage(text: String) = _ui.update { it.copy(message = text) }

    // ===== 內部 =====

    /** 背景執行一件工作；[block] 回傳要顯示的訊息 (null = 不顯示)，完成後重新整理清單 */
    private fun work(busyRes: Int, block: suspend () -> String?) {
        _ui.update { it.copy(busy = context.getString(busyRes), error = null, message = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block() } }
            _ui.update {
                it.copy(
                    busy = null,
                    message = result.getOrNull(),
                    error = result.exceptionOrNull()?.let(::describe)
                )
            }
            refresh()
        }
    }

    private fun decodeImage(entry: VaultEntry): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        store.open(entry.id).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        // 縮到最長邊約 2048，避免大張證件掃描圖吃光記憶體
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
        return store.open(entry.id).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }

    private fun describeUri(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.let { name = it }
        }
        return name to System.currentTimeMillis()
    }

    private fun describe(e: Throwable): String = when (e) {
        is VaultWrongPasswordException -> context.getString(R.string.vault_wrong_password)
        is VaultCorruptedException -> context.getString(R.string.vault_corrupted)
        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")
        fun isImage(name: String) = name.substringAfterLast('.', "").lowercase() in imageExtensions
    }
}
