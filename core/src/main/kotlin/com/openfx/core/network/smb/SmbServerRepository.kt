package com.openfx.core.network.smb

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.openfx.core.security.SecretCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.smbDataStore by preferencesDataStore(name = "openfx_smb_servers")

/**
 * 已儲存的 SMB 伺服器清單
 *
 * 伺服器設定以 JSON 存在 DataStore；密碼用 [SecretCipher] (Android Keystore) 加密，
 * 以 "pw_<id>" 另外存放，清單本身不含密碼。
 */
@Singleton
class SmbServerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: SecretCipher
) {
    private val serversKey = stringPreferencesKey("servers")
    private val json = Json { ignoreUnknownKeys = true }
    private val listSerializer = ListSerializer(SmbServer.serializer())

    val servers: Flow<List<SmbServer>> = context.smbDataStore.data.map { prefs ->
        decode(prefs[serversKey])
    }

    /** 新增或更新；[password] 為 null 表示保留原密碼 (編輯時沒改密碼) */
    suspend fun save(server: SmbServer, password: String?): SmbServer {
        val saved = if (server.id.isBlank()) server.copy(id = UUID.randomUUID().toString()) else server
        context.smbDataStore.edit { prefs ->
            val list = decode(prefs[serversKey]).filterNot { it.id == saved.id } + saved
            prefs[serversKey] = json.encodeToString(listSerializer, list)
            if (password != null) {
                val key = passwordKey(saved.id)
                if (password.isEmpty()) prefs.remove(key) else prefs[key] = cipher.encrypt(password)
            }
        }
        return saved
    }

    suspend fun delete(id: String) {
        context.smbDataStore.edit { prefs ->
            val list = decode(prefs[serversKey]).filterNot { it.id == id }
            prefs[serversKey] = json.encodeToString(listSerializer, list)
            prefs.remove(passwordKey(id))
        }
    }

    /** 記下伺服器不提供共享清單 (瀏覽時第一次列舉失敗後呼叫) */
    suspend fun markNoShareList(hostKey: String) {
        context.smbDataStore.edit { prefs ->
            val list = decode(prefs[serversKey]).map {
                if (it.hostKey == hostKey) it.copy(listsShares = false) else it
            }
            prefs[serversKey] = json.encodeToString(listSerializer, list)
        }
    }

    /** 依 smb:// 路徑的 host[:port] 找伺服器與解密後的密碼 */
    suspend fun credentialsFor(hostKey: String): SmbCredentials? {
        val prefs = context.smbDataStore.data.first()
        val server = decode(prefs[serversKey]).firstOrNull { it.hostKey == hostKey } ?: return null
        val password = prefs[passwordKey(server.id)]?.let(cipher::decrypt).orEmpty()
        return SmbCredentials(server, password)
    }

    private fun passwordKey(id: String) = stringPreferencesKey("pw_$id")

    private fun decode(raw: String?): List<SmbServer> =
        raw?.let { runCatching { json.decodeFromString(listSerializer, it) }.getOrNull() }.orEmpty()
}
