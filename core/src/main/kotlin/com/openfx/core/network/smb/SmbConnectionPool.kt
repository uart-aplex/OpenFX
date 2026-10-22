package com.openfx.core.network.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.security.bc.BCSecurityProvider
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** smb:// 路徑對應的伺服器沒有存在清單裡 */
class SmbServerNotFoundException(val hostKey: String) :
    IOException("SMB server not configured: $hostKey")

/** 帳號或密碼錯誤 */
class SmbAuthException(val hostKey: String, cause: Throwable) :
    IOException("SMB login failed: $hostKey", cause)

/** 伺服器不允許列舉共享 (部分 NAS / 精簡 SMB 實作)，需要使用者自行輸入共享名稱 */
class SmbShareListUnavailableException(val hostKey: String, cause: Throwable) :
    IOException("SMB share list unavailable: $hostKey", cause)

/** 共享不存在或沒有權限 */
class SmbAccessDeniedException(val target: String, cause: Throwable) :
    IOException("SMB access denied: $target", cause)

/**
 * SMB 連線池 — 每台伺服器一條 Session，每個共享一個 DiskShare，重複使用
 *
 * 連線可能因 NAS 休眠、Wi-Fi 切換而中斷；[withShare] 遇到連線類錯誤會丟掉快取、
 * 重新登入再試一次，對呼叫端透明。
 */
@Singleton
class SmbConnectionPool @Inject constructor(
    private val servers: SmbServerRepository
) {
    private val client = SMBClient(
        SmbConfig.builder()
            // Android 的 JCE 沒有 NTLM 需要的 MD4，改用 BouncyCastle
            .withSecurityProvider(BCSecurityProvider())
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .withMultiProtocolNegotiate(true)
            .build()
    )

    private class HostEntry(val connection: Connection, val session: Session) {
        val shares = mutableMapOf<String, DiskShare>()
    }

    private val hosts = mutableMapOf<String, HostEntry>()
    private val lock = Mutex()

    /** 在指定共享上執行 [block]；連線中斷時自動重連一次 */
    suspend fun <T> withShare(path: SmbPath, block: (DiskShare) -> T): T =
        withContext(Dispatchers.IO) {
            try {
                block(share(path))
            } catch (e: Exception) {
                if (!isConnectionError(e)) throw mapError(path, e)
                invalidate(path.hostKey)
                try {
                    block(share(path))
                } catch (retry: Exception) {
                    throw mapError(path, retry)
                }
            }
        }

    /** 列出伺服器上可瀏覽的磁碟共享 (排除 C$、IPC$ 這類隱藏/系統共享) */
    suspend fun listShares(path: SmbPath): List<String> = withContext(Dispatchers.IO) {
        suspend fun enumerate(): List<String> = enumerateShares(host(path.hostKey).session)
        try {
            enumerate()
        } catch (e: Exception) {
            if (!isConnectionError(e)) throw shareListError(path, e)
            invalidate(path.hostKey)
            try {
                enumerate()
            } catch (retry: Exception) {
                throw shareListError(path, retry)
            }
        }
    }

    private fun enumerateShares(session: Session): List<String> {
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        return ServerService(transport).shares1
            .filter { (it.type and STYPE_MASK) == STYPE_DISKTREE && !it.netName.endsWith("$") }
            .map { it.netName }
            .sortedBy { it.lowercase() }
    }

    /** 已登入成功卻列不出共享 → 多半是伺服器不支援 SRVSVC，改請使用者輸入共享名稱 */
    private fun shareListError(path: SmbPath, e: Throwable): Throwable {
        val mapped = mapError(path, e)
        return if (mapped is SmbAuthException || mapped is SmbServerNotFoundException || isConnectionError(e)) {
            mapped
        } else {
            SmbShareListUnavailableException(path.hostKey, e)
        }
    }

    /**
     * 用指定憑證試登入 (新增伺服器時的「測試連線」)
     * 成功回傳可見的共享清單；登入成功但伺服器不給清單時回傳 null。
     */
    suspend fun testLogin(server: SmbServer, password: String): Result<List<String>?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = client.connect(server.host, server.port)
                try {
                    val session = authenticate(connection, server, password)
                    runCatching { enumerateShares(session) }.getOrNull()
                } finally {
                    runCatching { connection.close() }
                }
            }.recoverCatching { throw mapError(SmbPath(server.host, server.port), it) }
        }

    /** 伺服器設定變更或刪除後呼叫，下次存取時重新登入 */
    suspend fun invalidate(hostKey: String) {
        lock.withLock {
            hosts.remove(hostKey)?.let { entry ->
                runCatching { entry.session.close() }
                runCatching { entry.connection.close() }
            }
        }
    }

    private suspend fun share(path: SmbPath): DiskShare {
        require(!path.isServerRoot) { "No share in path: $path" }
        val entry = host(path.hostKey)
        return lock.withLock {
            entry.shares[path.share]?.takeIf { it.isConnected }
                ?: (entry.session.connectShare(path.share) as? DiskShare
                    ?: throw IOException("Not a disk share: ${path.share}"))
                    .also { entry.shares[path.share] = it }
        }
    }

    private suspend fun host(hostKey: String): HostEntry {
        val credentials = servers.credentialsFor(hostKey) ?: throw SmbServerNotFoundException(hostKey)
        return lock.withLock {
            hosts[hostKey]?.takeIf { it.connection.isConnected }?.let { return@withLock it }
            hosts.remove(hostKey)
            val server = credentials.server
            val connection = client.connect(server.host, server.port)
            val session = try {
                authenticate(connection, server, credentials.password)
            } catch (e: Exception) {
                runCatching { connection.close() }
                throw e
            }
            HostEntry(connection, session).also { hosts[hostKey] = it }
        }
    }

    private fun authenticate(connection: Connection, server: SmbServer, password: String): Session {
        if (server.username.isNotBlank()) {
            return connection.authenticate(
                AuthenticationContext(
                    server.username,
                    password.toCharArray(),
                    server.domain.ifBlank { null }
                )
            )
        }
        // 沒填帳號：先試訪客，不行再試匿名 (不同 NAS 接受的方式不同)
        return runCatching { connection.authenticate(AuthenticationContext.guest()) }
            .getOrElse { connection.authenticate(AuthenticationContext.anonymous()) }
    }

    private fun isConnectionError(e: Throwable): Boolean {
        val status = (e as? SMBApiException)?.status
        if (status == NtStatus.STATUS_NETWORK_NAME_DELETED ||
            status == NtStatus.STATUS_USER_SESSION_DELETED ||
            status == NtStatus.STATUS_CONNECTION_DISCONNECTED ||
            status == NtStatus.STATUS_CONNECTION_RESET
        ) return true
        // smbj 的傳輸錯誤會包在 SMBRuntimeException / TransportException 裡
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is com.hierynomus.protocol.transport.TransportException ||
                cause is java.net.SocketException ||
                cause is java.io.EOFException
            ) return true
            cause = cause.cause
        }
        return false
    }

    private fun mapError(path: SmbPath, e: Throwable): Throwable {
        if (e is SmbServerNotFoundException || e is SmbAuthException || e is SmbAccessDeniedException) return e
        return when ((e as? SMBApiException)?.status) {
            NtStatus.STATUS_LOGON_FAILURE,
            NtStatus.STATUS_LOGON_TYPE_NOT_GRANTED,
            NtStatus.STATUS_PASSWORD_EXPIRED,
            NtStatus.STATUS_ACCOUNT_DISABLED -> SmbAuthException(path.hostKey, e)
            NtStatus.STATUS_ACCESS_DENIED,
            NtStatus.STATUS_BAD_NETWORK_NAME,
            NtStatus.STATUS_BAD_NETWORK_PATH -> SmbAccessDeniedException(path.toString(), e)
            else -> e
        }
    }

    private companion object {
        /** SHARE_INFO_1.shi1_type：低位元組 0 = 磁碟共享 */
        const val STYPE_MASK = 0xFF
        const val STYPE_DISKTREE = 0
    }
}
