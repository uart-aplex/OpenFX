package com.openfx.core.file

import kotlinx.serialization.Serializable

/**
 * 檔案項目資料模型 — 手機和 TV 共用
 */
@Serializable
data class FileItem(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val mimeType: String? = null,
    val extension: String = name.substringAfterLast('.', ""),
    val isHidden: Boolean = name.startsWith(".")
) {
    val fileType: FileType
        get() = FileType.fromExtension(extension)

    val formattedSize: String
        get() = when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${"%.1f".format(size / 1024.0)} KB"
            size < 1024 * 1024 * 1024 -> "${"%.1f".format(size / (1024.0 * 1024))} MB"
            else -> "${"%.2f".format(size / (1024.0 * 1024 * 1024))} GB"
        }
}

enum class FileType {
    FOLDER, IMAGE, VIDEO, AUDIO, DOCUMENT, ARCHIVE, APK, CODE, OTHER;

    companion object {
        private val imageExts = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "svg", "heic", "heif")
        private val videoExts = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "3gp", "ts")
        private val audioExts = setOf("mp3", "flac", "wav", "aac", "ogg", "wma", "m4a", "opus")
        private val docExts = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "csv")
        private val archiveExts = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst")
        private val codeExts = setOf("kt", "java", "py", "js", "ts", "c", "cpp", "h", "xml", "json", "yaml", "html", "css")

        fun fromExtension(ext: String): FileType {
            val lower = ext.lowercase()
            return when {
                lower == "" -> FOLDER
                lower in imageExts -> IMAGE
                lower in videoExts -> VIDEO
                lower in audioExts -> AUDIO
                lower in docExts -> DOCUMENT
                lower in archiveExts -> ARCHIVE
                lower == "apk" -> APK
                lower in codeExts -> CODE
                else -> OTHER
            }
        }
    }
}

/**
 * 儲存空間資訊
 */
data class StorageVolume(
    val name: String,
    val path: String,
    val totalBytes: Long,
    val usedBytes: Long,
    val isRemovable: Boolean,
    val isUsb: Boolean = false
) {
    val freeBytes: Long get() = totalBytes - usedBytes
    val usagePercent: Float get() = if (totalBytes > 0) usedBytes.toFloat() / totalBytes else 0f
}

/**
 * 複製/移動進度
 */
data class TransferProgress(
    val currentFile: String,
    val bytesTransferred: Long,
    val totalBytes: Long,
    val filesCompleted: Int,
    val totalFiles: Int
) {
    val percent: Float
        get() = if (totalBytes > 0) bytesTransferred.toFloat() / totalBytes else 0f
}
