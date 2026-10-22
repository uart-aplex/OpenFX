package com.openfx.core.file

/** 檔案瀏覽器排序：資料夾一律排在檔案前面，各自依 [sortBy] 排序 (本機與 SMB 共用) */
fun List<FileItem>.sortedForBrowser(sortBy: SortBy, ascending: Boolean): List<FileItem> {
    fun List<FileItem>.sortedBy(): List<FileItem> {
        val sorted = when (sortBy) {
            SortBy.NAME -> sortedBy { it.name.lowercase() }
            SortBy.SIZE -> sortedBy { it.size }
            SortBy.DATE -> sortedBy { it.lastModified }
            SortBy.TYPE -> sortedBy { it.extension }
        }
        return if (ascending) sorted else sorted.reversed()
    }
    val (dirs, files) = partition { it.isDirectory }
    return dirs.sortedBy() + files.sortedBy()
}
