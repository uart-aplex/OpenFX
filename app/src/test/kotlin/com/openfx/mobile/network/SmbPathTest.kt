package com.openfx.mobile.network

import com.openfx.core.file.FileItem
import com.openfx.core.file.FileType
import com.openfx.core.file.SortBy
import com.openfx.core.file.sortedForBrowser
import com.openfx.core.network.smb.SmbPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmbPathTest {

    @Test
    fun parsesServerShareAndSubPath() {
        val path = SmbPath.parse("smb://nas.local/Media/Movies/2024/a b#1.mkv")
        assertEquals("nas.local", path.host)
        assertEquals(445, path.port)
        assertEquals("Media", path.share)
        assertEquals("Movies/2024/a b#1.mkv", path.subPath)
        assertEquals("Movies\\2024\\a b#1.mkv", path.smbjPath)
        assertEquals("a b#1.mkv", path.name)
    }

    @Test
    fun customPortIsKeptInHostKeyAndString() {
        val path = SmbPath.parse("smb://192.168.0.10:4445/share")
        assertEquals(4445, path.port)
        assertEquals("192.168.0.10:4445", path.hostKey)
        assertEquals("smb://192.168.0.10:4445/share", path.toString())
        assertTrue(path.isShareRoot)
    }

    @Test
    fun roundTripsAndNormalizesSlashes() {
        val original = "smb://Host/share/a/b"
        assertEquals(original, SmbPath.parse(original).toString())
        assertEquals("smb://Host/share/a/b", SmbPath.parse("smb://Host//share\\a\\b/").toString())
        // 主機名稱不分大小寫
        assertEquals("host", SmbPath.parse(original).hostKey)
    }

    @Test
    fun parentWalksUpToServerRoot() {
        var path: SmbPath? = SmbPath.parse("smb://h/s/a/b")
        val seen = mutableListOf<String>()
        while (path != null) {
            seen += path.toString()
            path = path.parent()
        }
        assertEquals(listOf("smb://h/s/a/b", "smb://h/s/a", "smb://h/s", "smb://h"), seen)
        assertTrue(SmbPath.parse("smb://h").isServerRoot)
        assertNull(SmbPath.parse("smb://h").parent())
    }

    @Test
    fun childBuildsShareThenSubPath() {
        val root = SmbPath.parse("smb://h")
        assertEquals("smb://h/s", root.child("s").toString())
        assertEquals("smb://h/s/x", root.child("s").child("x").toString())
        assertEquals("smb://h/s/x/y", root.child("s").child("x").child("y").toString())
    }

    @Test
    fun recognisesSmbPathsOnly() {
        assertTrue(SmbPath.isSmb("smb://h/s"))
        assertTrue(SmbPath.isSmb("SMB://h/s"))
        assertFalse(SmbPath.isSmb("/storage/emulated/0/smb://x"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathWithoutHost() {
        SmbPath.parse("smb:///share")
    }

    @Test
    fun foldersSortBeforeFilesAndFolderTypeIgnoresDots() {
        val items = listOf(
            FileItem("b.txt", "/b.txt", 5, 2, isDirectory = false),
            FileItem("com.example.app", "/com.example.app", 0, 1, isDirectory = true),
            FileItem("a.txt", "/a.txt", 9, 3, isDirectory = false),
            FileItem("README", "/README", 1, 4, isDirectory = false),
        )
        val sorted = items.sortedForBrowser(SortBy.NAME, ascending = true)
        assertEquals(listOf("com.example.app", "a.txt", "b.txt", "README"), sorted.map { it.name })
        assertEquals(FileType.FOLDER, sorted[0].fileType)
        // 沒有副檔名的檔案不是資料夾
        assertEquals(FileType.OTHER, sorted[3].fileType)

        val bySizeDesc = items.sortedForBrowser(SortBy.SIZE, ascending = false)
        assertEquals("com.example.app", bySizeDesc.first().name)
        assertEquals(listOf("a.txt", "b.txt", "README"), bySizeDesc.drop(1).map { it.name })
    }
}
