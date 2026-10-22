package com.openfx.core.network.smb

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.share.DiskShare
import com.openfx.core.file.FileItem
import com.openfx.core.file.FileOperations
import com.openfx.core.file.SortBy
import com.openfx.core.file.StorageVolume
import com.openfx.core.file.TransferProgress
import com.openfx.core.file.VfsBackend
import com.openfx.core.file.sortedForBrowser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SMB 檔案操作 — 處理 smb:// 路徑
 *
 * copy/move 由 [com.openfx.core.file.RoutingFileOperations] 透過 [VfsBackend] 統一處理
 * (可能跨本機/SMB)，這裡只實作單一檔案系統內的操作。
 */
@Singleton
class SmbFileOperations @Inject constructor(
    private val pool: SmbConnectionPool
) : FileOperations, VfsBackend {

    override suspend fun listFiles(
        path: String,
        showHidden: Boolean,
        sortBy: SortBy,
        ascending: Boolean
    ): Result<List<FileItem>> = runCatching {
        list(path)
            .filter { showHidden || !it.isHidden }
            .sortedForBrowser(sortBy, ascending)
    }

    override suspend fun list(path: String): List<FileItem> {
        val smb = SmbPath.parse(path)
        if (smb.isServerRoot) {
            return pool.listShares(smb).map { share ->
                FileItem(
                    name = share,
                    path = smb.child(share).toString(),
                    size = 0,
                    lastModified = 0,
                    isDirectory = true,
                    isHidden = false
                )
            }
        }
        return pool.withShare(smb) { share ->
            share.list(smb.smbjPath)
                .filter { it.fileName != "." && it.fileName != ".." }
                .map { info ->
                    val attributes = info.fileAttributes
                    val isDir = attributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                    FileItem(
                        name = info.fileName,
                        path = smb.child(info.fileName).toString(),
                        size = if (isDir) 0 else info.endOfFile,
                        lastModified = info.lastWriteTime.toEpochMillis(),
                        isDirectory = isDir,
                        isHidden = info.fileName.startsWith(".") ||
                            attributes and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L
                    )
                }
        }
    }

    override suspend fun stat(path: String): FileItem? {
        val smb = SmbPath.parse(path)
        if (smb.isServerRoot || smb.isShareRoot) {
            return FileItem(smb.name, smb.toString(), 0, 0, isDirectory = true, isHidden = false)
        }
        return pool.withShare(smb) { share ->
            try {
                val info = share.getFileInformation(smb.smbjPath)
                val isDir = info.standardInformation.isDirectory
                FileItem(
                    name = smb.name,
                    path = smb.toString(),
                    size = if (isDir) 0 else info.standardInformation.endOfFile,
                    lastModified = info.basicInformation.lastWriteTime.toEpochMillis(),
                    isDirectory = isDir,
                    isHidden = smb.name.startsWith(".")
                )
            } catch (e: SMBApiException) {
                if (e.isNotFound()) null else throw e
            }
        }
    }

    override suspend fun openRead(path: String): InputStream {
        val smb = SmbPath.parse(path)
        return pool.withShare(smb) { share ->
            val file = share.openFile(
                smb.smbjPath,
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
            SmbRangeInputStream(file, file.fileInformation.standardInformation.endOfFile)
        }
    }

    override suspend fun openWrite(path: String): OutputStream {
        val smb = SmbPath.parse(path)
        return pool.withShare(smb) { share ->
            val file = share.openFile(
                smb.smbjPath,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                null,
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                null
            )
            object : FilterOutputStream(file.outputStream) {
                // FilterOutputStream 預設逐 byte 寫入，大檔案會非常慢
                override fun write(b: ByteArray, off: Int, len: Int) {
                    out.write(b, off, len)
                }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        file.close()
                    }
                }
            }
        }
    }

    override suspend fun mkdirs(path: String) {
        val smb = SmbPath.parse(path)
        if (smb.isServerRoot || smb.isShareRoot) return
        pool.withShare(smb) { share ->
            var current = ""
            smb.subPath.split('/').forEach { segment ->
                current = if (current.isEmpty()) segment else "$current\\$segment"
                if (share.entryKind(current) == null) share.mkdir(current)
            }
        }
    }

    override suspend fun deleteRecursively(path: String) {
        val smb = SmbPath.parse(path)
        require(!smb.isServerRoot && !smb.isShareRoot) { "Refusing to delete a share root: $path" }
        pool.withShare(smb) { share -> share.deleteEntry(smb.smbjPath) }
    }

    override fun child(dir: String, name: String): String = SmbPath.parse(dir).child(name).toString()

    // ===== FileOperations =====

    override suspend fun copy(sources: List<String>, destination: String): Flow<TransferProgress> =
        throw UnsupportedOperationException("Use RoutingFileOperations for SMB copy")

    override suspend fun move(sources: List<String>, destination: String): Flow<TransferProgress> =
        throw UnsupportedOperationException("Use RoutingFileOperations for SMB move")

    override suspend fun delete(paths: List<String>): Result<Int> = runCatching {
        paths.count { path ->
            deleteRecursively(path)
            true
        }
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> = runCatching {
        require(newName.isNotBlank() && '/' !in newName && '\\' !in newName) { "Invalid name: $newName" }
        val smb = SmbPath.parse(path)
        val parent = smb.parent() ?: throw IOException("Cannot rename server root")
        val target = parent.child(newName)
        renameWithinShare(smb, target)
        stat(target.toString()) ?: throw IOException("Renamed entry not found: $target")
    }

    /**
     * 同一共享內搬移/改名 (伺服器端完成，不經過手機傳資料)
     *
     * 有些伺服器不允許對開著的 handle 改名 (例如 Windows 上的精簡 SMB 實作)，
     * 這時退回「複製後刪除」；真的沒有權限的話複製/刪除也會失敗，錯誤照常回報。
     */
    suspend fun renameWithinShare(from: SmbPath, to: SmbPath) {
        require(from.hostKey == to.hostKey && from.share == to.share) { "Not the same share" }
        require(!from.isShareRoot && !from.isServerRoot) { "Cannot rename a share root" }
        val renamed = pool.withShare(from) { share ->
            if (share.entryKind(to.smbjPath) != null) {
                throw IOException("Already exists: ${to.name}")
            }
            try {
                share.open(
                    from.smbjPath,
                    EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ),
                    null,
                    SMB2ShareAccess.ALL,
                    SMB2CreateDisposition.FILE_OPEN,
                    null
                ).use { entry -> entry.rename(to.smbjPath) }
                true
            } catch (e: SMBApiException) {
                if (e.status != NtStatus.STATUS_ACCESS_DENIED) throw e
                false
            }
        }
        if (!renamed) {
            copyTree(from.toString(), to.toString())
            deleteRecursively(from.toString())
        }
    }

    private suspend fun copyTree(from: String, to: String) {
        val item = stat(from) ?: throw IOException("Not found: $from")
        if (item.isDirectory) {
            mkdirs(to)
            list(from).forEach { child -> copyTree(child.path, child(to, child.name)) }
        } else {
            openRead(from).use { input -> openWrite(to).use { output -> input.copyTo(output, 256 * 1024) } }
        }
    }

    override suspend fun createDirectory(parentPath: String, name: String): Result<FileItem> = runCatching {
        val parent = SmbPath.parse(parentPath)
        require(!parent.isServerRoot) { "Cannot create a share" }
        val target = parent.child(name)
        pool.withShare(target) { share ->
            if (share.entryKind(target.smbjPath) != null) {
                throw IOException("Already exists: $name")
            }
            share.mkdir(target.smbjPath)
        }
        FileItem(name, target.toString(), 0, System.currentTimeMillis(), isDirectory = true, isHidden = false)
    }

    override suspend fun getStorageVolumes(): List<StorageVolume> = emptyList()

    override suspend fun search(rootPath: String, query: String, recursive: Boolean): Flow<FileItem> {
        if (query.isBlank()) return emptyFlow()
        val lower = query.lowercase()
        return flow {
            suspend fun walk(dir: String, depth: Int) {
                val children = runCatching { list(dir) }.getOrDefault(emptyList())
                for (item in children) {
                    if (item.name.lowercase().contains(lower)) emit(item)
                    // 網路搜尋限制深度，避免整台 NAS 掃到天荒地老
                    if (recursive && item.isDirectory && !item.isHidden && depth < MAX_SEARCH_DEPTH) {
                        walk(item.path, depth + 1)
                    }
                }
            }
            walk(rootPath, 0)
        }
    }

    override suspend fun getFileInfo(path: String): Result<FileItem> = runCatching {
        stat(path) ?: throw IOException("Not found: $path")
    }

    private fun DiskShare.deleteEntry(path: String) {
        when (entryKind(path)) {
            EntryKind.DIRECTORY -> rmdir(path, true)
            EntryKind.FILE -> rm(path)
            null -> Unit // 已經不存在
        }
    }

    private enum class EntryKind { FILE, DIRECTORY }

    /**
     * 路徑是檔案、資料夾或不存在 (null)
     *
     * 不用 smbj 的 fileExists/folderExists：它只把 OBJECT_NAME_NOT_FOUND 當成不存在，
     * 部分 NAS / 精簡 SMB 實作回的是 STATUS_NO_SUCH_FILE，會直接丟例外。
     */
    private fun DiskShare.entryKind(path: String): EntryKind? = try {
        if (getFileInformation(path).standardInformation.isDirectory) EntryKind.DIRECTORY else EntryKind.FILE
    } catch (e: SMBApiException) {
        if (e.isNotFound()) null else throw e
    }

    private fun SMBApiException.isNotFound(): Boolean =
        status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND ||
            status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ||
            status == NtStatus.STATUS_NO_SUCH_FILE

    private companion object {
        const val MAX_SEARCH_DEPTH = 6
    }
}

/**
 * 依 offset 讀取到檔案大小為止，不會對伺服器發出超過結尾的 READ
 * (smbj 內建的 InputStream 會多讀一次換 STATUS_END_OF_FILE，有些精簡 SMB 實作處理不好)。
 * 關閉串流時一併關閉遠端檔案 handle。
 */
private class SmbRangeInputStream(
    private val file: com.hierynomus.smbj.share.File,
    private val size: Long
) : InputStream() {
    private var position = 0L

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (position >= size) return -1
        val count = file.read(b, position, off, minOf(len.toLong(), size - position).toInt())
        if (count <= 0) return -1
        position += count
        return count
    }

    override fun available(): Int = minOf(size - position, Int.MAX_VALUE.toLong()).toInt()

    override fun close() {
        file.close()
    }
}
