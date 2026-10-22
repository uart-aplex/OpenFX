package com.openfx.core.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * 跨檔案系統複製/移動需要的最小操作集合 — 本機與 SMB 各自實作
 *
 * [RoutingFileOperations] 用這組 API 做「本機 ↔ SMB」「SMB ↔ SMB」的串流複製，
 * 純本機複製仍走 [LocalFileOperations] 原本的實作。
 */
interface VfsBackend {
    /** 路徑不存在回傳 null */
    suspend fun stat(path: String): FileItem?

    /** 列出目錄內容 (含隱藏檔，不排序) */
    suspend fun list(path: String): List<FileItem>

    suspend fun openRead(path: String): InputStream

    /** 建立或覆寫檔案 */
    suspend fun openWrite(path: String): OutputStream

    /** 建立目錄 (含上層) */
    suspend fun mkdirs(path: String)

    /** 刪除檔案或整個目錄 */
    suspend fun deleteRecursively(path: String)

    fun child(dir: String, name: String): String
}

/** 本機檔案系統的 [VfsBackend] */
object LocalVfsBackend : VfsBackend {

    override suspend fun stat(path: String): FileItem? = withContext(Dispatchers.IO) {
        File(path).takeIf { it.exists() }?.toItem()
    }

    override suspend fun list(path: String): List<FileItem> = withContext(Dispatchers.IO) {
        File(path).listFiles()?.map { it.toItem() }.orEmpty()
    }

    override suspend fun openRead(path: String): InputStream = withContext(Dispatchers.IO) {
        File(path).inputStream()
    }

    override suspend fun openWrite(path: String): OutputStream = withContext(Dispatchers.IO) {
        File(path).also { it.parentFile?.mkdirs() }.outputStream()
    }

    override suspend fun mkdirs(path: String) {
        withContext(Dispatchers.IO) {
            val dir = File(path)
            if (!dir.isDirectory && !dir.mkdirs()) throw java.io.IOException("Cannot create directory: $path")
        }
    }

    override suspend fun deleteRecursively(path: String) {
        withContext(Dispatchers.IO) { File(path).deleteRecursively() }
    }

    override fun child(dir: String, name: String): String = File(dir, name).absolutePath

    private fun File.toItem() = FileItem(
        name = name,
        path = absolutePath,
        size = if (isDirectory) 0 else length(),
        lastModified = lastModified(),
        isDirectory = isDirectory,
        isHidden = isHidden
    )
}
