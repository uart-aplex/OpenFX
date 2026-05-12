package com.openfx.core.network

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.share.DiskShare
import com.openfx.core.file.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SMB 連線設定
 */
data class SmbConfig(
    val host: String,
    val shareName: String,
    val username: String = "",
    val password: String = "",
    val domain: String = ""
)

/**
 * SMB2/3 客戶端 — 連線 NAS 和 PC 共享資料夾
 * 使用 smbj 函式庫，支援 SMB2 和 SMB3
 */
@Singleton
class Smb2Client @Inject constructor() {

    private var client: SMBClient? = null
    private var share: DiskShare? = null
    private var currentConfig: SmbConfig? = null

    /**
     * 連線到 SMB 共享
     */
    suspend fun connect(config: SmbConfig): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            disconnect()

            val smbClient = SMBClient()
            val connection = smbClient.connect(config.host)
            val authContext = if (config.username.isNotEmpty()) {
                AuthenticationContext(
                    config.username,
                    config.password.toCharArray(),
                    config.domain.ifEmpty { null }
                )
            } else {
                AuthenticationContext.anonymous()
            }

            val session = connection.authenticate(authContext)
            val diskShare = session.connectShare(config.shareName) as DiskShare

            client = smbClient
            share = diskShare
            currentConfig = config
        }
    }

    /**
     * 列出 SMB 路徑下的檔案
     */
    suspend fun listFiles(path: String): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val diskShare = share ?: throw IllegalStateException("Not connected")
            val smbPath = path.trimStart('/')

            diskShare.list(smbPath.ifEmpty { "" })
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { info ->
                    val isDir = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    val fullPath = if (smbPath.isEmpty()) info.fileName else "$smbPath/${info.fileName}"
                    FileItem(
                        name = info.fileName,
                        path = fullPath,
                        size = info.endOfFile,
                        lastModified = info.changeTime.toEpochMillis(),
                        isDirectory = isDir,
                        extension = info.fileName.substringAfterLast('.', "")
                    )
                }
        }
    }

    /**
     * 從 SMB 下載檔案到本機
     */
    suspend fun downloadFile(remotePath: String, localFile: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val diskShare = share ?: throw IllegalStateException("Not connected")

                val remoteFile = diskShare.openFile(
                    remotePath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )

                remoteFile.use { rf ->
                    rf.inputStream.use { input ->
                        localFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 8192)
                        }
                    }
                }
                Unit
            }
        }

    /**
     * 上傳本機檔案到 SMB
     */
    suspend fun uploadFile(localFile: File, remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val diskShare = share ?: throw IllegalStateException("Not connected")

                val remoteFile = diskShare.openFile(
                    remotePath,
                    EnumSet.of(AccessMask.GENERIC_WRITE),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_WRITE),
                    SMB2CreateDisposition.FILE_OVERWRITE_IF,
                    null
                )

                remoteFile.use { rf ->
                    localFile.inputStream().use { input ->
                        rf.outputStream.use { output ->
                            input.copyTo(output, bufferSize = 8192)
                        }
                    }
                }
                Unit
            }
        }

    /**
     * 串流讀取遠端檔案（用於直接播放影片/音樂）
     */
    suspend fun openFileStream(remotePath: String): Result<InputStream> =
        withContext(Dispatchers.IO) {
            runCatching {
                val diskShare = share ?: throw IllegalStateException("Not connected")
                val remoteFile = diskShare.openFile(
                    remotePath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
                remoteFile.inputStream
            }
        }

    fun disconnect() {
        try {
            share?.close()
            client?.close()
        } catch (_: Exception) {
        }
        share = null
        client = null
        currentConfig = null
    }

    val isConnected: Boolean get() = share != null
}
