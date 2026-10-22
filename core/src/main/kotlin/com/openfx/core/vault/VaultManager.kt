package com.openfx.core.vault

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class VaultState { NOT_CREATED, LOCKED, UNLOCKED }

/**
 * App 層的保險箱 — 持有唯一的 [VaultStore]，負責上鎖狀態、自動上鎖與暫存檔清理
 *
 * 保險箱放在 noBackupFilesDir：其他 App 讀不到，也不會被 Android 雲端備份帶走。
 * 解密出來給外部 App 檢視的暫存檔放在 cache/vault_view，上鎖時一律刪除。
 */
@Singleton
class VaultManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // 建立/改密碼時依本機速度決定 PBKDF2 次數 (約 1 秒)
    val store = VaultStore(File(context.noBackupFilesDir, "vault")) { VaultCrypto.calibrateIterations() }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lockJob: Job? = null

    private val _state = MutableStateFlow(currentState())
    val state: StateFlow<VaultState> = _state.asStateFlow()

    /** 解密給外部 App 檢視用的暫存資料夾 */
    val viewDir: File get() = File(context.cacheDir, "vault_view")

    init {
        // 上次沒正常上鎖 (例如被系統殺掉) 留下的暫存明文
        scope.launch { viewDir.deleteRecursively() }
    }

    suspend fun create(password: CharArray) = withContext(Dispatchers.IO) {
        try {
            store.create(password)
        } finally {
            password.fill('\u0000')
        }
        refresh()
    }

    /** 密碼錯誤丟 [VaultWrongPasswordException] */
    suspend fun unlock(password: CharArray) = withContext(Dispatchers.IO) {
        try {
            store.unlock(password)
        } finally {
            password.fill('\u0000')
        }
        refresh()
    }

    suspend fun unlockWithKey(key: ByteArray) = withContext(Dispatchers.IO) {
        store.unlockWithKey(key)
        refresh()
    }

    /** 寫出加密備份 (不需要解鎖) */
    suspend fun backup(output: java.io.OutputStream) = withContext(Dispatchers.IO) {
        output.use { store.backupTo(it) }
    }

    /** 從備份還原 (取代現有保險箱)；還原後為上鎖狀態 */
    suspend fun restore(input: java.io.InputStream) = withContext(Dispatchers.IO) {
        lock()
        input.use { VaultStore.restore(it, store.root) }
        refresh()
    }

    /** 沒有系統檔案選擇器 (多數 TV) 時，備份/還原改用 Download/OpenFX */
    val fallbackBackupDir: File
        get() = File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "OpenFX"
        )

    fun lock() {
        lockJob?.cancel()
        store.lock()
        viewDir.deleteRecursively()
        refresh()
    }

    /** 離開保險箱畫面後延遲上鎖 (選檔、拍照等會短暫離開畫面，不立刻上鎖) */
    fun scheduleLock(delayMillis: Long = AUTO_LOCK_MILLIS) {
        lockJob?.cancel()
        lockJob = scope.launch {
            delay(delayMillis)
            lock()
        }
    }

    fun cancelScheduledLock() {
        lockJob?.cancel()
        lockJob = null
    }

    private fun refresh() {
        _state.value = currentState()
    }

    private fun currentState(): VaultState = when {
        !store.isCreated -> VaultState.NOT_CREATED
        store.isUnlocked -> VaultState.UNLOCKED
        else -> VaultState.LOCKED
    }

    companion object {
        const val AUTO_LOCK_MILLIS = 60_000L
        const val MIN_PASSWORD_LENGTH = 6
    }
}
