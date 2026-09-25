package com.openfx.core.file

import com.github.junrar.Archive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * 壓縮檔項目
 */
data class ArchiveEntry(
    val name: String,
    val size: Long,
    val isDirectory: Boolean
)

/**
 * 壓縮檔瀏覽與解壓
 *
 * 支援格式：zip / rar / 7z / tar / tar.gz (.tgz) / tar.bz2 (.tbz2)
 * (tar.xz / tar.zst 需要額外原生函式庫,暫不支援)
 *
 * 解壓時對每個項目路徑做 zip-slip 防護 (拒絕 .. 與絕對路徑)。
 */
object ArchiveOperations {

    enum class Kind { ZIP, RAR, SEVEN_Z, TAR, TAR_GZ, TAR_BZ2 }

    fun kindOf(path: String): Kind? {
        val lower = path.lowercase()
        return when {
            lower.endsWith(".zip") -> Kind.ZIP
            lower.endsWith(".rar") -> Kind.RAR
            lower.endsWith(".7z") -> Kind.SEVEN_Z
            lower.endsWith(".tar") -> Kind.TAR
            lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> Kind.TAR_GZ
            lower.endsWith(".tar.bz2") || lower.endsWith(".tbz2") -> Kind.TAR_BZ2
            else -> null
        }
    }

    fun isSupported(path: String): Boolean = kindOf(path) != null

    /** 列出壓縮檔內容 */
    suspend fun listEntries(path: String): Result<List<ArchiveEntry>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                require(file.isFile) { "檔案不存在：$path" }

                val entries = when (kindOf(path)
                    ?: throw IllegalArgumentException("不支援的壓縮格式 (xz/zst 暫不支援)")) {
                    Kind.ZIP -> listZip(file)
                    Kind.RAR -> listRar(file)
                    Kind.SEVEN_Z -> list7z(file)
                    Kind.TAR, Kind.TAR_GZ, Kind.TAR_BZ2 -> listTar(file)
                }
                entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            }
        }

    /**
     * 全部解壓到 destDir (自動建立)。
     * onProgress(已解壓檔案數) — 單次掃描,無法預知總數所以不回報總數。
     * 回傳解壓的檔案數。
     */
    suspend fun extractAll(
        archivePath: String,
        destDirPath: String,
        onProgress: (extractedCount: Int) -> Unit = {}
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val destDir = File(destDirPath).apply { mkdirs() }
            require(destDir.isDirectory) { "無法建立目錄：$destDirPath" }
            var count = 0

            fun writeTo(input: InputStream, entryName: String) {
                val relative = safeRelativePath(entryName) ?: return
                val target = File(destDir, relative)
                target.parentFile?.mkdirs()
                input.use { inp ->
                    target.outputStream().use { out -> inp.copyTo(out, bufferSize = 64 * 1024) }
                }
                count++
                onProgress(count)
            }

            when (kindOf(archivePath)
                ?: throw IllegalArgumentException("不支援的壓縮格式")) {
                Kind.ZIP -> {
                    ZipFile.builder().setFile(archivePath).get().use { zip ->
                        val entries = zip.entries
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.isDirectory) continue
                            writeTo(zip.getInputStream(entry), entry.name)
                        }
                    }
                }

                Kind.RAR -> {
                    Archive(File(archivePath)).use { archive ->
                        while (true) {
                            val header = archive.nextFileHeader() ?: break
                            if (header.isDirectory) continue
                            val relative = safeRelativePath(header.fileName) ?: continue
                            val target = File(destDir, relative)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out ->
                                archive.extractFile(header, out)
                            }
                            count++
                            onProgress(count)
                        }
                    }
                }

                Kind.SEVEN_Z -> {
                    SevenZFile.builder().setFile(archivePath).get().use { sevenZ ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val entry = sevenZ.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val relative = safeRelativePath(entry.name) ?: continue
                            val target = File(destDir, relative)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out ->
                                while (true) {
                                    val read = sevenZ.read(buffer)
                                    if (read <= 0) break
                                    out.write(buffer, 0, read)
                                }
                            }
                            count++
                            onProgress(count)
                        }
                    }
                }

                Kind.TAR, Kind.TAR_GZ, Kind.TAR_BZ2 -> {
                    openTarStream(archivePath).use { tar ->
                        while (true) {
                            val entry = tar.nextEntry ?: break
                            if (entry.isDirectory) continue
                            val relative = safeRelativePath(entry.name) ?: continue
                            val target = File(destDir, relative)
                            target.parentFile?.mkdirs()
                            target.outputStream().use { out ->
                                tar.copyTo(out, bufferSize = 64 * 1024)
                            }
                            count++
                            onProgress(count)
                        }
                    }
                }
            }

            count
        }
    }

    // ===== Private =====

    private fun listZip(file: File): List<ArchiveEntry> {
        ZipFile.builder().setFile(file).get().use { zip ->
            val result = mutableListOf<ArchiveEntry>()
            val entries = zip.entries
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                result.add(ArchiveEntry(entry.name, entry.size.coerceAtLeast(0), entry.isDirectory))
            }
            return result
        }
    }

    private fun listRar(file: File): List<ArchiveEntry> {
        Archive(file).use { archive ->
            val result = mutableListOf<ArchiveEntry>()
            while (true) {
                val header = archive.nextFileHeader() ?: break
                result.add(
                    ArchiveEntry(
                        header.fileName,
                        header.fullUnpackSize.coerceAtLeast(0),
                        header.isDirectory
                    )
                )
            }
            return result
        }
    }

    private fun list7z(file: File): List<ArchiveEntry> {
        SevenZFile.builder().setFile(file).get().use { sevenZ ->
            val result = mutableListOf<ArchiveEntry>()
            while (true) {
                val entry = sevenZ.nextEntry ?: break
                result.add(ArchiveEntry(entry.name, entry.size.coerceAtLeast(0), entry.isDirectory))
            }
            return result
        }
    }

    private fun listTar(file: File): List<ArchiveEntry> {
        openTarStream(file.absolutePath).use { tar ->
            val result = mutableListOf<ArchiveEntry>()
            while (true) {
                val entry = tar.nextEntry ?: break
                result.add(ArchiveEntry(entry.name, entry.size.coerceAtLeast(0), entry.isDirectory))
            }
            return result
        }
    }

    private fun openTarStream(path: String): TarArchiveInputStream {
        val raw = FileInputStream(path).buffered(64 * 1024)
        val stream: InputStream = when (kindOf(path)) {
            Kind.TAR_GZ -> GzipCompressorInputStream(raw)
            Kind.TAR_BZ2 -> BZip2CompressorInputStream(raw)
            else -> raw
        }
        return TarArchiveInputStream(stream)
    }

    /**
     * zip-slip 防護 — 把壓縮檔內的路徑轉成安全的相對路徑：
     * 去掉磁碟機代號、開頭斜線、. 與 .. 段落；不合法回傳 null (略過)
     */
    private fun safeRelativePath(name: String): String? {
        val segments = name
            .replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." && !it.endsWith(":") }
        return if (segments.isEmpty()) null else segments.joinToString("/")
    }
}
