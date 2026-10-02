package com.openfx.mobile.apps

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class AppPackageArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun preservesBaseAndEverySplitExactly() {
        val files = (0..3).map { index ->
            temp.newFile("source_$index.apk").apply { writeBytes(ByteArray(512 + index) { (it + index).toByte() }) }
        }
        val archive = temp.newFile("app.apks")
        AppPackageArchive.write(files, archive)
        ZipFile(archive).use { zip ->
            val entries = AppPackageArchive.entries(zip)
            assertEquals(listOf("base.apk", "split_1.apk", "split_2.apk", "split_3.apk"), entries.map { it.name })
            entries.forEachIndexed { index, entry ->
                assertArrayEquals(files[index].readBytes(), zip.getInputStream(entry).use { it.readBytes() })
            }
        }
    }

    @Test fun rejectsMissingBaseAndUnsafeArchivePaths() {
        listOf("split_1.apk", "../base.apk", "nested/base.apk", "base.apk/", "base.apk.exe").forEachIndexed { index, name ->
            val archive = makeArchive("invalid_$index.apks", listOf(name))
            ZipFile(archive).use { zip ->
                try {
                    AppPackageArchive.entries(zip)
                    fail("Accepted invalid archive: $name")
                } catch (_: IllegalArgumentException) { }
            }
        }
    }

    @Test fun deletesPartialArchiveOnCancellation() {
        val source = temp.newFile("source.apk").apply { writeBytes(ByteArray(65536)) }
        val archive = temp.newFile("cancelled.apks")
        var checks = 0
        try {
            AppPackageArchive.write(listOf(source), archive) {
                if (++checks >= 3) throw InterruptedException("cancelled")
            }
            fail("Expected cancellation")
        } catch (_: InterruptedException) { }
        assertFalse(archive.exists())
    }

    @Test fun rejectsEmptyAndExcessiveSets() {
        listOf(emptyList<String>(), (0..128).map { if (it == 0) "base.apk" else "split_$it.apk" }).forEachIndexed { i, names ->
            ZipFile(makeArchive("oversized_$i.apks", names)).use { zip ->
                try {
                    AppPackageArchive.entries(zip)
                    fail("Expected archive rejection")
                } catch (_: IllegalArgumentException) { }
            }
        }
    }

    private fun makeArchive(name: String, entries: List<String>): File = temp.newFile(name).apply {
        ZipOutputStream(outputStream()).use { zip ->
            entries.forEach {
                zip.putNextEntry(ZipEntry(it))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }
    }
}
