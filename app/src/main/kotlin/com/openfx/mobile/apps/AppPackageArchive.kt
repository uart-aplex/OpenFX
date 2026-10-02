package com.openfx.mobile.apps

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** A flat APK set: base.apk plus every installed split, with original signatures intact. */
object AppPackageArchive {
    const val MAX_APK_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024

    fun write(apks: List<File>, destination: File, checkActive: () -> Unit = {}) {
        require(apks.isNotEmpty() && apks.size <= 128)
        require(apks.all { it.isFile && it.canRead() && it.length() in 1..MAX_APK_BYTES })
        require(apks.sumOf { it.length() } <= MAX_TOTAL_BYTES)
        try {
            ZipOutputStream(destination.outputStream().buffered()).use { zip ->
                zip.setLevel(0)
                apks.forEachIndexed { index, file ->
                    checkActive()
                    zip.putNextEntry(ZipEntry(if (index == 0) "base.apk" else "split_$index.apk"))
                    file.inputStream().buffered().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            checkActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            zip.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }

    fun entries(zip: ZipFile): List<ZipEntry> {
        val entries = mutableListOf<ZipEntry>()
        val enumeration = zip.entries()
        while (enumeration.hasMoreElements()) {
            require(entries.size < 128) { "Too many APKs" }
            val entry = enumeration.nextElement()
            require(!entry.isDirectory && entry.name.matches(Regex("[A-Za-z0-9_.-]+\\.apk"))) {
                "Invalid APK entry"
            }
            require(entry.size in 1..MAX_APK_BYTES) { "Invalid APK size" }
            require(entries.none { it.name == entry.name }) { "Duplicate APK entry" }
            entries += entry
        }
        require(entries.any { it.name == "base.apk" }) { "Missing base.apk" }
        require(entries.sumOf { it.size } <= MAX_TOTAL_BYTES) { "APK set too large" }
        return entries.sortedBy { if (it.name == "base.apk") "" else it.name }
    }
}
