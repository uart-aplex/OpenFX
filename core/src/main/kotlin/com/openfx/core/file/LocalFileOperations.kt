package com.openfx.core.file

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 本機檔案操作實作
 */
@Singleton
class LocalFileOperations @Inject constructor(
    @ApplicationContext private val context: Context
) : FileOperations {

    override suspend fun listFiles(
        path: String,
        showHidden: Boolean,
        sortBy: SortBy,
        ascending: Boolean
    ): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                throw IllegalArgumentException("Path is not a valid directory: $path")
            }

            val files = dir.listFiles()?.toList() ?: emptyList()

            files.filter { showHidden || !it.isHidden }
                .map { it.toFileItem() }
                .let { list ->
                    // 資料夾優先排在前面
                    val dirs = list.filter { it.isDirectory }
                    val regular = list.filter { !it.isDirectory }

                    val sortedDirs = sortList(dirs, sortBy, ascending)
                    val sortedFiles = sortList(regular, sortBy, ascending)
                    sortedDirs + sortedFiles
                }
        }
    }

    override suspend fun copy(
        sources: List<String>,
        destination: String
    ): Flow<TransferProgress> = flow {
        val sourceFiles = sources.map { File(it) }
        val destDir = File(destination)

        if (!destDir.exists()) destDir.mkdirs()

        val totalSize = sourceFiles.sumOf { calculateSize(it) }
        var transferred = 0L
        var completed = 0

        sourceFiles.forEach { source ->
            val target = File(destDir, source.name)
            if (source.isDirectory) {
                source.copyRecursively(target, overwrite = true) { _, _ ->
                    // 可以在此加入更細緻的進度追蹤
                    OnErrorAction.SKIP
                }
            } else {
                source.inputStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            transferred += read
                            emit(
                                TransferProgress(
                                    currentFile = source.name,
                                    bytesTransferred = transferred,
                                    totalBytes = totalSize,
                                    filesCompleted = completed,
                                    totalFiles = sources.size
                                )
                            )
                        }
                    }
                }
            }
            completed++
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun move(
        sources: List<String>,
        destination: String
    ): Flow<TransferProgress> = flow {
        val destDir = File(destination)
        if (!destDir.exists()) destDir.mkdirs()

        sources.forEachIndexed { index, sourcePath ->
            val source = File(sourcePath)
            val target = File(destDir, source.name)

            // 嘗試直接 rename（同一檔案系統下很快）
            val moved = source.renameTo(target)
            if (!moved) {
                // 不同檔案系統：複製再刪除
                source.copyRecursively(target, overwrite = true)
                source.deleteRecursively()
            }

            emit(
                TransferProgress(
                    currentFile = source.name,
                    bytesTransferred = 0,
                    totalBytes = 0,
                    filesCompleted = index + 1,
                    totalFiles = sources.size
                )
            )
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun delete(paths: List<String>): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            var count = 0
            paths.forEach { path ->
                val file = File(path)
                val deleted = if (file.isDirectory) file.deleteRecursively() else file.delete()
                if (deleted) count++
            }
            count
        }
    }

    override suspend fun rename(path: String, newName: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                val newFile = File(file.parent, newName)
                if (newFile.exists()) throw IllegalStateException("File already exists: $newName")
                if (!file.renameTo(newFile)) throw IllegalStateException("Rename failed")
                newFile.toFileItem()
            }
        }

    override suspend fun createDirectory(parentPath: String, name: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(parentPath, name)
                if (dir.exists()) throw IllegalStateException("Directory already exists: $name")
                if (!dir.mkdirs()) throw IllegalStateException("Failed to create directory")
                dir.toFileItem()
            }
        }

    override suspend fun getStorageVolumes(): List<StorageVolume> = withContext(Dispatchers.IO) {
        val volumes = mutableListOf<StorageVolume>()

        // 內部儲存
        val internal = Environment.getExternalStorageDirectory()
        val statFs = StatFs(internal.path)
        volumes.add(
            StorageVolume(
                name = "內部儲存",
                path = internal.absolutePath,
                totalBytes = statFs.totalBytes,
                usedBytes = statFs.totalBytes - statFs.availableBytes,
                isRemovable = false
            )
        )

        // 外部儲存 (SD 卡)
        val externalDirs = context.getExternalFilesDirs(null)
        externalDirs.drop(1).forEach { dir ->
            if (dir != null) {
                try {
                    val sdStatFs = StatFs(dir.path)
                    // 取得實際的 SD 卡根目錄
                    val sdRoot = dir.path.substringBefore("/Android")
                    volumes.add(
                        StorageVolume(
                            name = "SD 卡",
                            path = sdRoot,
                            totalBytes = sdStatFs.totalBytes,
                            usedBytes = sdStatFs.totalBytes - sdStatFs.availableBytes,
                            isRemovable = true
                        )
                    )
                } catch (_: Exception) {
                    // SD 卡可能未掛載
                }
            }
        }

        volumes
    }

    override suspend fun search(
        rootPath: String,
        query: String,
        recursive: Boolean
    ): Flow<FileItem> = flow {
        val root = File(rootPath)
        val lowerQuery = query.lowercase()

        suspend fun searchDir(dir: File) {
            dir.listFiles()?.forEach { file ->
                if (file.name.lowercase().contains(lowerQuery)) {
                    emit(file.toFileItem())
                }
                if (recursive && file.isDirectory && !file.isHidden) {
                    searchDir(file)
                }
            }
        }
        searchDir(root)
    }.flowOn(Dispatchers.IO)

    override suspend fun getFileInfo(path: String): Result<FileItem> =
        withContext(Dispatchers.IO) {
            runCatching { File(path).toFileItem() }
        }

    // ---- Private helpers ----

    private fun File.toFileItem(): FileItem {
        val ext = extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return FileItem(
            name = name,
            path = absolutePath,
            size = if (isDirectory) 0 else length(),
            lastModified = lastModified(),
            isDirectory = isDirectory,
            mimeType = mime,
            extension = ext,
            isHidden = isHidden
        )
    }

    private fun sortList(
        list: List<FileItem>,
        sortBy: SortBy,
        ascending: Boolean
    ): List<FileItem> {
        val sorted = when (sortBy) {
            SortBy.NAME -> list.sortedBy { it.name.lowercase() }
            SortBy.SIZE -> list.sortedBy { it.size }
            SortBy.DATE -> list.sortedBy { it.lastModified }
            SortBy.TYPE -> list.sortedBy { it.extension }
        }
        return if (ascending) sorted else sorted.reversed()
    }

    private fun calculateSize(file: File): Long {
        return if (file.isDirectory) {
            file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            file.length()
        }
    }
}
