package com.openfx.core.network.smb

/**
 * SMB 路徑 — 檔案瀏覽器裡統一用字串表示：
 *
 *   smb://host            → 伺服器根 (列出共享)
 *   smb://host:4445/share → 共享根目錄
 *   smb://host/share/a/b  → 共享內的路徑
 *
 * [subPath] 以 "/" 分隔、不含開頭斜線；送進 smbj 前用 [smbjPath] 轉成反斜線。
 */
data class SmbPath(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val share: String = "",
    val subPath: String = ""
) {
    /** 伺服器識別 key — 對應 [SmbServer.hostKey] */
    val hostKey: String get() = hostKey(host, port)

    val isServerRoot: Boolean get() = share.isEmpty()
    val isShareRoot: Boolean get() = share.isNotEmpty() && subPath.isEmpty()

    val name: String
        get() = when {
            subPath.isNotEmpty() -> subPath.substringAfterLast('/')
            share.isNotEmpty() -> share
            else -> host
        }

    /** smbj 使用的共享內路徑 (反斜線分隔，根目錄為空字串) */
    val smbjPath: String get() = subPath.replace('/', '\\')

    fun child(name: String): SmbPath = when {
        share.isEmpty() -> copy(share = name)
        subPath.isEmpty() -> copy(subPath = name)
        else -> copy(subPath = "$subPath/$name")
    }

    /** 上一層；伺服器根沒有上一層 */
    fun parent(): SmbPath? = when {
        subPath.contains('/') -> copy(subPath = subPath.substringBeforeLast('/'))
        subPath.isNotEmpty() -> copy(subPath = "")
        share.isNotEmpty() -> copy(share = "")
        else -> null
    }

    override fun toString(): String = buildString {
        append(SCHEME)
        append(host)
        if (port != DEFAULT_PORT) append(':').append(port)
        if (share.isNotEmpty()) append('/').append(share)
        if (subPath.isNotEmpty()) append('/').append(subPath)
    }

    companion object {
        const val SCHEME = "smb://"
        const val DEFAULT_PORT = 445

        fun isSmb(path: String): Boolean = path.startsWith(SCHEME, ignoreCase = true)

        fun hostKey(host: String, port: Int): String =
            if (port == DEFAULT_PORT) host.lowercase() else "${host.lowercase()}:$port"

        /** 解析 smb:// 路徑；格式錯誤丟 IllegalArgumentException */
        fun parse(path: String): SmbPath {
            require(isSmb(path)) { "Not an SMB path: $path" }
            val rest = path.substring(SCHEME.length).replace('\\', '/')
            // smb:///share 這種沒有主機的路徑不能把 share 當成主機
            val authority = rest.substringBefore('/')
            require(authority.isNotBlank()) { "SMB path has no host: $path" }
            val parts = listOf(authority) + rest.substringAfter('/', "")
                .split('/')
                .filter { it.isNotEmpty() }

            // IPv6 位址不支援 (區網 NAS 幾乎都是 IPv4 / 主機名稱)
            val host = authority.substringBefore(':')
            val port = authority.substringAfter(':', "").toIntOrNull() ?: DEFAULT_PORT
            require(host.isNotBlank()) { "SMB path has no host: $path" }

            return SmbPath(
                host = host,
                port = port,
                share = parts.getOrNull(1).orEmpty(),
                subPath = parts.drop(2).joinToString("/")
            )
        }
    }
}
