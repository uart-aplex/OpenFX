package com.openfx.core.file

import kotlinx.coroutines.flow.Flow

/**
 * 檔案操作介面 — 本機和遠端共用
 * 所有實作都必須是 suspend 函式，支援在 IO dispatcher 上執行
 */
interface FileOperations {

    /** 列出指定路徑下的所有檔案和資料夾 */
    suspend fun listFiles(
        path: String,
        showHidden: Boolean = false,
        sortBy: SortBy = SortBy.NAME,
        ascending: Boolean = true
    ): Result<List<FileItem>>

    /** 複製檔案/資料夾到目標路徑 */
    suspend fun copy(
        sources: List<String>,
        destination: String,
    ): Flow<TransferProgress>

    /** 移動檔案/資料夾到目標路徑 */
    suspend fun move(
        sources: List<String>,
        destination: String,
    ): Flow<TransferProgress>

    /** 刪除檔案/資料夾 */
    suspend fun delete(paths: List<String>): Result<Int>

    /** 重命名檔案/資料夾 */
    suspend fun rename(path: String, newName: String): Result<FileItem>

    /** 建立新資料夾 */
    suspend fun createDirectory(parentPath: String, name: String): Result<FileItem>

    /** 取得儲存空間資訊 */
    suspend fun getStorageVolumes(): List<StorageVolume>

    /** 搜尋檔案 */
    suspend fun search(
        rootPath: String,
        query: String,
        recursive: Boolean = true
    ): Flow<FileItem>

    /** 取得檔案詳細資訊 */
    suspend fun getFileInfo(path: String): Result<FileItem>
}

enum class SortBy {
    NAME, SIZE, DATE, TYPE
}
