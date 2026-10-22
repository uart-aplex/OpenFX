package com.openfx.mobile.vault

import com.openfx.core.vault.VaultCorruptedException
import com.openfx.core.vault.VaultStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VaultBackupTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("vault-backup").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun backupRestoresOnAnotherDeviceWithTheSamePassword() {
        val original = VaultStore(File(dir, "a/vault"), iterations = 1000)
        original.create("pw-123".toCharArray())
        val folder = original.createFolder(null, "IDs")
        original.addFile(folder.id, "passport.jpg", 0, "passport-bytes".byteInputStream())
        val backup = ByteArrayOutputStream().also(original::backupTo).toByteArray()

        // 另一台裝置 (另一個目錄) 已有不同的保險箱 → 還原後整個取代
        val otherRoot = File(dir, "b/vault")
        VaultStore(otherRoot, iterations = 1000).apply {
            create("other".toCharArray())
            addFile(null, "old.txt", 0, "old".byteInputStream())
        }
        VaultStore.restore(ByteArrayInputStream(backup), otherRoot)

        val restored = VaultStore(otherRoot, iterations = 1000)
        restored.unlock("pw-123".toCharArray())
        val ids = restored.list(null).single()
        assertEquals("IDs", ids.name)
        val passport = restored.list(ids.id).single()
        assertEquals("passport-bytes", restored.open(passport.id).use { it.readBytes().decodeToString() })
        assertFalse(File(dir, "b/vault.restore").exists())
    }

    @Test
    fun restoreIgnoresPathTraversalEntries() {
        val evil = ByteArrayOutputStream()
        ZipOutputStream(evil).use { zip ->
            zip.putNextEntry(ZipEntry("../../evil.txt"))
            zip.write("pwned".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("vault.json"))
            zip.write("""{"salt":"00","iterations":1,"wrappedKey":"00"}""".toByteArray())
            zip.closeEntry()
        }
        val root = File(dir, "x/vault")
        VaultStore.restore(ByteArrayInputStream(evil.toByteArray()), root)
        assertFalse(File(dir, "evil.txt").exists())
        assertFalse(File(root.parentFile, "evil.txt").exists())
    }

    @Test(expected = VaultCorruptedException::class)
    fun rejectsNonBackupZip() {
        val notBackup = ByteArrayOutputStream()
        ZipOutputStream(notBackup).use { zip ->
            zip.putNextEntry(ZipEntry("photo.jpg"))
            zip.write(1)
            zip.closeEntry()
        }
        VaultStore.restore(ByteArrayInputStream(notBackup.toByteArray()), File(dir, "y/vault"))
    }
}
