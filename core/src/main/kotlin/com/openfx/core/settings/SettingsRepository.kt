package com.openfx.core.settings

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.openfx.core.file.SortBy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "openfx_settings")

/**
 * App 設定 — DataStore 持久化
 *
 * 檔案瀏覽器 (預設檢視/排序/隱藏檔案) 和傳輸 (裝置名稱/通知) 的
 * 真實設定來源；設定頁寫入，各功能模組讀取。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden_files")
        val VIEW_GRID = booleanPreferencesKey("default_view_grid")
        val SORT_BY = stringPreferencesKey("default_sort_by")
        val SORT_ASC = booleanPreferencesKey("default_sort_ascending")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val TRANSFER_NOTIFY = booleanPreferencesKey("transfer_complete_notify")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val RECEIVE_PIN = stringPreferencesKey("receive_pin")
    }

    /** 未設定時的預設裝置名稱 (廠牌 + 型號) */
    val fallbackDeviceName: String
        get() = listOf(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .trim()
            .ifBlank { "OpenFX Device" }

    val showHiddenFiles: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.SHOW_HIDDEN] ?: false }

    val defaultViewGrid: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.VIEW_GRID] ?: true }

    val sortBy: Flow<SortBy> =
        context.settingsDataStore.data.map { prefs ->
            runCatching { SortBy.valueOf(prefs[Keys.SORT_BY] ?: "NAME") }
                .getOrDefault(SortBy.NAME)
        }

    val sortAscending: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.SORT_ASC] ?: true }

    /** 裝置名稱 — 其他 OpenFX 裝置掃描時看到的名字 */
    val deviceName: Flow<String> =
        context.settingsDataStore.data.map { prefs ->
            prefs[Keys.DEVICE_NAME]?.takeIf { it.isNotBlank() } ?: fallbackDeviceName
        }

    /** 接收完成時是否在通知顯示檔案資訊 */
    val transferCompleteNotify: Flow<Boolean> =
        context.settingsDataStore.data.map { it[Keys.TRANSFER_NOTIFY] ?: true }

    suspend fun setShowHiddenFiles(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.SHOW_HIDDEN] = value }
    }

    suspend fun setDefaultViewGrid(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.VIEW_GRID] = value }
    }

    suspend fun setSortBy(value: SortBy) {
        context.settingsDataStore.edit { it[Keys.SORT_BY] = value.name }
    }

    suspend fun setSortAscending(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.SORT_ASC] = value }
    }

    suspend fun setDeviceName(value: String) {
        context.settingsDataStore.edit { it[Keys.DEVICE_NAME] = value.trim() }
    }

    suspend fun setTransferCompleteNotify(value: Boolean) {
        context.settingsDataStore.edit { it[Keys.TRANSFER_NOTIFY] = value }
    }

    // ===== 傳檔配對 =====

    /** 本機固定識別碼 — 讓傳送端在 IP 變動後仍認得這台接收端 (第一次讀取時產生) */
    suspend fun deviceId(): String = getOrCreate(Keys.DEVICE_ID) {
        UUID.randomUUID().toString()
    }

    /** 本機接收用的 6 位數配對碼 (第一次讀取時產生) */
    val receivePin: Flow<String?> =
        context.settingsDataStore.data.map { it[Keys.RECEIVE_PIN] }

    suspend fun receivePinOrCreate(): String = getOrCreate(Keys.RECEIVE_PIN, ::newPin)

    suspend fun regenerateReceivePin(): String {
        val pin = newPin()
        context.settingsDataStore.edit { it[Keys.RECEIVE_PIN] = pin }
        return pin
    }

    /** 對其他接收端輸入過且驗證成功的配對碼，key 為對方 deviceId (舊版接收端用 ip:port) */
    suspend fun peerPin(peerKey: String): String? =
        context.settingsDataStore.data.first()[peerPinKey(peerKey)]

    suspend fun setPeerPin(peerKey: String, pin: String?) {
        context.settingsDataStore.edit {
            if (pin == null) it.remove(peerPinKey(peerKey)) else it[peerPinKey(peerKey)] = pin
        }
    }

    private fun peerPinKey(peerKey: String) = stringPreferencesKey("peer_pin_$peerKey")

    private fun newPin(): String = "%06d".format(SecureRandom().nextInt(1_000_000))

    private suspend fun getOrCreate(
        key: Preferences.Key<String>,
        create: () -> String
    ): String {
        var value: String? = null
        context.settingsDataStore.edit { prefs ->
            value = prefs[key] ?: create().also { prefs[key] = it }
        }
        return value!!
    }
}
