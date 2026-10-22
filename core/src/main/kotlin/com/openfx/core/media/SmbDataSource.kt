package com.openfx.core.media

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.File as SmbFile
import com.openfx.core.network.smb.SmbConnectionPool
import com.openfx.core.network.smb.SmbPath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.runBlocking
import java.io.EOFException
import java.io.File
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

/** smb:// 路徑 ↔ 播放器用的 Uri (檔名中的 # ? 空白都要正確編碼) */
object MediaUris {
    fun forPath(path: String): Uri {
        if (!SmbPath.isSmb(path)) return Uri.fromFile(File(path))
        val smb = SmbPath.parse(path)
        return Uri.Builder()
            .scheme("smb")
            .encodedAuthority(if (smb.port == SmbPath.DEFAULT_PORT) smb.host else "${smb.host}:${smb.port}")
            .apply {
                appendPath(smb.share)
                smb.subPath.split('/').filter { it.isNotEmpty() }.forEach { appendPath(it) }
            }
            .build()
    }

    fun toSmbPath(uri: Uri): SmbPath {
        val segments = uri.pathSegments
        return SmbPath(
            host = uri.host.orEmpty(),
            port = if (uri.port > 0) uri.port else SmbPath.DEFAULT_PORT,
            share = segments.firstOrNull().orEmpty(),
            subPath = segments.drop(1).joinToString("/")
        )
    }
}

/**
 * 直接從 SMB 串流播放 (不先下載) — 用 offset 隨機讀取，所以影片可以拖曳進度
 *
 * 每次 SMB READ 都是一次網路往返，所以內部用 [READ_AHEAD] 的緩衝一次多讀一些。
 */
@UnstableApi
class SmbDataSource(private val pool: SmbConnectionPool) : BaseDataSource(/* isNetwork = */ true) {

    private var uri: Uri? = null
    private var file: SmbFile? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var fileSize = 0L
    private var opened = false

    private val buffer = ByteArray(READ_AHEAD)
    private var bufferStart = 0L
    private var bufferLength = 0

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val smb = MediaUris.toSmbPath(dataSpec.uri)
        val remote = runBlocking {
            pool.withShare(smb) { share ->
                share.openFile(
                    smb.smbjPath,
                    EnumSet.of(AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                )
            }
        }
        file = remote
        val size = remote.fileInformation.standardInformation.endOfFile
        fileSize = size
        if (dataSpec.position > size) throw EOFException("Seek past end: ${dataSpec.position} > $size")

        position = dataSpec.position
        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            size - dataSpec.position
        }
        bufferLength = 0
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        // 緩衝區沒有目前位置的資料 → 從 SMB 一次讀一大段
        if (position < bufferStart || position >= bufferStart + bufferLength) {
            val current = file ?: return C.RESULT_END_OF_INPUT
            // 不要讀超過檔案結尾 (部分 SMB 伺服器對越界 READ 的回應有問題)
            val toRead = minOf(buffer.size.toLong(), fileSize - position).toInt()
            if (toRead <= 0) return C.RESULT_END_OF_INPUT
            val read = current.read(buffer, position, 0, toRead)
            if (read <= 0) return C.RESULT_END_OF_INPUT
            bufferStart = position
            bufferLength = read
        }

        val available = (bufferStart + bufferLength - position).toInt()
        val count = minOf(length.toLong(), available.toLong(), bytesRemaining).toInt()
        System.arraycopy(buffer, (position - bufferStart).toInt(), target, offset, count)
        position += count
        bytesRemaining -= count
        bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            file?.close()
        } catch (_: Exception) {
            // 連線已斷就不用關了
        } finally {
            file = null
            bufferLength = 0
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private companion object {
        const val READ_AHEAD = 512 * 1024
    }
}

/**
 * 播放器用的 DataSource.Factory — smb:// 走 [SmbDataSource]，其他 (本機檔案、http…) 走預設實作
 */
@UnstableApi
@Singleton
class OpenFxDataSourceFactory @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pool: SmbConnectionPool
) : DataSource.Factory {

    override fun createDataSource(): DataSource = RoutingDataSource()

    private inner class RoutingDataSource : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var active: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            val delegate = if (dataSpec.uri.scheme == "smb") {
                SmbDataSource(pool)
            } else {
                DefaultDataSource.Factory(context).createDataSource()
            }
            listeners.forEach(delegate::addTransferListener)
            active = delegate
            return delegate.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            active?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = active?.uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            active?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                active?.close()
            } finally {
                active = null
            }
        }
    }
}
