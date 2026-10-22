package com.openfx.core.network.smb

import kotlinx.serialization.Serializable

/**
 * 使用者儲存的 SMB 伺服器 (NAS / Windows 共享)
 *
 * 密碼不放在這裡 — 由 [SmbServerRepository] 以 Android Keystore 加密後另外存放。
 */
@Serializable
data class SmbServer(
    val id: String,
    /** 顯示名稱；空白時用 host */
    val name: String = "",
    val host: String,
    val port: Int = SmbPath.DEFAULT_PORT,
    /** 空白 = 訪客 / 匿名登入 */
    val username: String = "",
    val domain: String = "",
    /** 預設開啟的共享；空白 = 先列出所有共享 */
    val share: String = "",
    /** 伺服器是否允許列出共享清單；false 時共享根目錄就是最上層，不能再往上 */
    val listsShares: Boolean = true
) {
    val displayName: String get() = name.ifBlank { host }

    val hostKey: String get() = SmbPath.hostKey(host, port)

    /** 點選伺服器時要開啟的路徑 */
    val rootPath: String
        get() = SmbPath(host = host, port = port, share = share.trim('/', '\\')).toString()
}

/** 連線用的完整憑證 (含解密後的密碼)，只在記憶體中使用 */
data class SmbCredentials(
    val server: SmbServer,
    val password: String
)
