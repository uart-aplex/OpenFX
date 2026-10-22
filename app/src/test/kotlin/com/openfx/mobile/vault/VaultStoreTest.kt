package com.openfx.mobile.vault

import com.openfx.core.vault.VaultLockedException
import com.openfx.core.vault.VaultStore
import com.openfx.core.vault.VaultWrongPasswordException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VaultStoreTest {

    private lateinit var dir: File
    private lateinit var vaultDir: File
    // 測試用低迭代次數，避免單元測試變慢
    private fun store() = VaultStore(vaultDir, iterations = 1000)

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("vault-test").toFile()
        vaultDir = File(dir, "vault")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun createAddReopenAndExport() {
        val passport = ByteArray(200_000) { (it % 251).toByte() }
        val source = File(dir, "src").apply { mkdirs() }
        File(source, "護照.jpg").writeBytes(passport)
        File(source, "證件").mkdirs()
        File(source, "證件/健保卡.png").writeText("card")

        store().apply {
            create("pw-1234".toCharArray())
            val docs = createFolder(null, "Documents")
            addLocal(docs.id, File(source, "護照.jpg"))
            addLocal(docs.id, File(source, "證件"))
            lock()
        }

        // 密文裡不能出現原始檔名
        val blobNames = File(vaultDir, "blobs").list().orEmpty().toList()
        assertTrue(blobNames.none { it.contains("護照") || it.contains("jpg") })
        assertFalse(File(vaultDir, "index.bin").readBytes().toString(Charsets.UTF_8).contains("Documents"))

        val reopened = store()
        reopened.unlock("pw-1234".toCharArray())
        val docs = reopened.list(null).single()
        assertEquals("Documents", docs.name)
        val inside = reopened.list(docs.id)
        assertEquals(listOf("證件", "護照.jpg"), inside.map { it.name })
        assertArrayEquals(passport, reopened.open(inside[1].id).use { it.readBytes() })

        val out = File(dir, "out")
        reopened.export(docs.id, out)
        assertArrayEquals(passport, File(out, "Documents/護照.jpg").readBytes())
        assertEquals("card", File(out, "Documents/證件/健保卡.png").readText())
    }

    @Test(expected = VaultWrongPasswordException::class)
    fun wrongPasswordIsRejected() {
        store().create("right".toCharArray())
        store().unlock("wrong".toCharArray())
    }

    @Test(expected = VaultLockedException::class)
    fun lockedVaultRefusesAccess() {
        val vault = store()
        vault.create("pw".toCharArray())
        vault.lock()
        vault.list(null)
    }

    @Test
    fun changePasswordKeepsData() {
        store().apply {
            create("old".toCharArray())
            addFile(null, "a.txt", 0, "hello".byteInputStream())
            changePassword("old".toCharArray(), "new".toCharArray())
            lock()
        }
        val vault = store()
        try {
            vault.unlock("old".toCharArray())
            throw AssertionError("old password still works")
        } catch (_: VaultWrongPasswordException) {
        }
        vault.unlock("new".toCharArray())
        assertEquals("hello", vault.open(vault.list(null).single().id).use { it.readBytes().decodeToString() })
    }

    @Test
    fun renameMoveDeleteAndNameConflicts() {
        val vault = store()
        vault.create("pw".toCharArray())
        val a = vault.createFolder(null, "A")
        val b = vault.createFolder(null, "B")
        val f1 = vault.addFile(a.id, "id.png", 0, "1".byteInputStream())
        val f2 = vault.addFile(b.id, "id.png", 0, "2".byteInputStream())
        // 同名搬移自動改名
        vault.move(listOf(f2.id), a.id)
        assertEquals(setOf("id.png", "id_1.png"), vault.list(a.id).map { it.name }.toSet())
        // 不能把資料夾搬進自己的子資料夾
        try {
            vault.move(listOf(a.id), a.id)
            throw AssertionError("moved folder into itself")
        } catch (_: java.io.IOException) {
        }
        vault.rename(f1.id, "passport.png")
        assertTrue(vault.list(a.id).any { it.name == "passport.png" })
        // 刪除資料夾連同內容與密文
        vault.delete(listOf(a.id))
        assertEquals(listOf("B"), vault.list(null).map { it.name })
        assertTrue(File(vaultDir, "blobs").list().orEmpty().isEmpty())
    }
}
