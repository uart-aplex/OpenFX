package com.openfx.core.file

import com.openfx.core.network.smb.SmbFileOperations
import com.openfx.core.network.smb.SmbPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 檔案瀏覽器實際注入的 FileOperations — 依路徑分派到本機或 SMB
 *
 * - 一般操作 (列表、刪除、改名…)：看路徑開頭是不是 smb:// 決定由誰處理
 * - 複製/移動：
 *   - 全部在本機 → 交給 [LocalFileOperations] (保留原本行為)
 *   - 同一個 SMB 共享內移動 → 伺服器端改名，不經手機傳資料
 *   - 其他 (本機↔SMB、不同共享) → 透過 [VfsBackend] 串流複製
 */
@Singleton
class RoutingFileOperations @Inject constructor(
    private val local: LocalFileOperations,
    private val smb: SmbFileOperations
) : FileOperations {

    private fun opsFor(path: String): FileOperations = if (SmbPath.isSmb(path)) smb else local

    private fun backendFor(path: String): VfsBackend = if (SmbPath.isSmb(path)) smb else LocalVfsBackend

    override suspend fun listFiles(
        path: String,
        showHidden: Boolean,
        sortBy: SortBy,
        ascending: Boolean
    ): Result<List<FileItem>> = opsFor(path).listFiles(path, showHidden, sortBy, ascending)

    override suspend fun copy(sources: List<String>, destination: String): Flow<TransferProgress> =
        if (sources.none(SmbPath::isSmb) && !SmbPath.isSmb(destination)) {
            local.copy(sources, destination)
        } else {
            streamCopy(sources, destination, deleteSources = false)
        }

    override suspend fun move(sources: List<String>, destination: String): Flow<TransferProgress> {
        if (sources.none(SmbPath::isSmb) && !SmbPath.isSmb(destination)) {
            return local.move(sources, destination)
        }
        if (SmbPath.isSmb(destination) && sources.all { sameShare(it, destination) }) {
            return flow {
                val destDir = SmbPath.parse(destination)
                sources.forEachIndexed { index, source ->
                    val from = SmbPath.parse(source)
                    if (from.parent() == destDir) return@forEachIndexed // 原地移動，什麼都不用做
                    val target = SmbPath.parse(uniqueChild(smb, destination, from.name))
                    smb.renameWithinShare(from, target)
                    emit(TransferProgress(from.name, 0, 0, index + 1, sources.size))
                }
            }
        }
        return streamCopy(sources, destination, deleteSources = true)
    }

    override suspend fun delete(paths: List<String>): Result<Int> = runCatching {
        val (remote, localPaths) = paths.partition(SmbPath::isSmb)
        var count = 0
        if (localPaths.isNotEmpty()) count += local.delete(localPaths).getOrThrow()
        if (remote.isNotEmpty()) count += smb.delete(remote).getOrThrow()
        count
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> =
        opsFor(path).rename(path, newName)

    override suspend fun createDirectory(parentPath: String, name: String): Result<FileItem> =
        opsFor(parentPath).createDirectory(parentPath, name)

    override suspend fun getStorageVolumes(): List<StorageVolume> = local.getStorageVolumes()

    override suspend fun search(rootPath: String, query: String, recursive: Boolean): Flow<FileItem> =
        opsFor(rootPath).search(rootPath, query, recursive)

    override suspend fun getFileInfo(path: String): Result<FileItem> = opsFor(path).getFileInfo(path)

    // ===== 跨檔案系統串流複製 =====

    private class Progress(val totalBytes: Long, val totalFiles: Int) {
        var bytes = 0L
        var filesDone = 0
    }

    private fun streamCopy(
        sources: List<String>,
        destination: String,
        deleteSources: Boolean
    ): Flow<TransferProgress> = flow {
        val destBackend = backendFor(destination)
        val items = sources.map { path ->
            backendFor(path).stat(path) ?: throw IOException("Not found: $path")
        }
        items.forEach { item ->
            // 不能把資料夾複製進自己裡面 (會無限遞迴)
            if (item.isDirectory && (destination == item.path || destination.startsWith(item.path + "/"))) {
                throw IOException("Cannot copy a folder into itself: ${item.name}")
            }
        }

        val progress = Progress(items.sumOf { sizeOf(it) }, items.size)
        destBackend.mkdirs(destination)

        for (item in items) {
            val target = uniqueChild(destBackend, destination, item.name)
            copyEntry(item, target, destBackend, progress)
            if (deleteSources) backendFor(item.path).deleteRecursively(item.path)
            progress.filesDone++
            emit(TransferProgress(item.name, progress.bytes, progress.totalBytes, progress.filesDone, progress.totalFiles))
        }
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<TransferProgress>.copyEntry(
        item: FileItem,
        target: String,
        destBackend: VfsBackend,
        progress: Progress
    ) {
        val srcBackend = backendFor(item.path)
        if (item.isDirectory) {
            destBackend.mkdirs(target)
            for (child in srcBackend.list(item.path)) {
                copyEntry(child, destBackend.child(target, child.name), destBackend, progress)
            }
            return
        }

        srcBackend.openRead(item.path).use { input ->
            destBackend.openWrite(target).use { output ->
                val buffer = ByteArray(COPY_BUFFER)
                var sinceEmit = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    progress.bytes += read
                    sinceEmit += read
                    // 網路複製時進度更新不需要太頻繁
                    if (sinceEmit >= EMIT_EVERY) {
                        sinceEmit = 0
                        emit(
                            TransferProgress(
                                item.name, progress.bytes, progress.totalBytes,
                                progress.filesDone, progress.totalFiles
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun sizeOf(item: FileItem): Long {
        if (!item.isDirectory) return item.size
        return backendFor(item.path).list(item.path).sumOf { sizeOf(it) }
    }

    /** 目標資料夾已有同名項目時加上 _copy / _copy2… (與本機複製相同規則) */
    private suspend fun uniqueChild(backend: VfsBackend, dir: String, name: String): String {
        val first = backend.child(dir, name)
        if (backend.stat(first) == null) return first

        val dot = name.lastIndexOf('.')
        val (base, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var counter = 1
        while (true) {
            val candidate = backend.child(dir, "${base}_copy${if (counter > 1) counter else ""}$ext")
            if (backend.stat(candidate) == null) return candidate
            counter++
        }
    }

    private fun sameShare(a: String, b: String): Boolean {
        if (!SmbPath.isSmb(a) || !SmbPath.isSmb(b)) return false
        val pa = SmbPath.parse(a)
        val pb = SmbPath.parse(b)
        return pa.hostKey == pb.hostKey && pa.share == pb.share && pa.share.isNotEmpty() && !pa.isShareRoot
    }

    private companion object {
        const val COPY_BUFFER = 256 * 1024
        const val EMIT_EVERY = 1024L * 1024
    }
}
