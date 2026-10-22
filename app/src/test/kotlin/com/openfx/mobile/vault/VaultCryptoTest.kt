package com.openfx.mobile.vault

import com.openfx.core.vault.VaultCorruptedException
import com.openfx.core.vault.VaultCrypto
import com.openfx.core.vault.VaultWrongPasswordException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class VaultCryptoTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun pbkdf2MatchesPublishedVectors() {
        // RFC 7914 §11 與常用的 PBKDF2-HMAC-SHA256 測試向量
        assertEquals(
            "120fb6cffcf8b32c43e7225256c4f837a86548c92ccc35480805987cb70be17b",
            hex(VaultCrypto.pbkdf2("password".toCharArray(), "salt".toByteArray(), 1, 32))
        )
        assertEquals(
            "c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a",
            hex(VaultCrypto.pbkdf2("password".toCharArray(), "salt".toByteArray(), 4096, 32))
        )
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(VaultCrypto.pbkdf2("passwd".toCharArray(), "salt".toByteArray(), 1, 64))
        )
    }

    @Test
    fun calibratedIterationsStayWithinBounds() {
        val rounds = VaultCrypto.calibrateIterations(targetMillis = 1_000)
        assert(rounds in VaultCrypto.MIN_ITERATIONS..VaultCrypto.MAX_ITERATIONS) { "rounds=$rounds" }
        // 目標時間極短時也不能低於下限
        assertEquals(VaultCrypto.MIN_ITERATIONS, VaultCrypto.calibrateIterations(targetMillis = 1))
    }

    @Test
    fun wrappedKeyNeedsTheRightPassword() {
        val salt = VaultCrypto.randomBytes(16)
        val dek = VaultCrypto.randomBytes(32)
        val wrapped = VaultCrypto.wrapKey(VaultCrypto.pbkdf2("correct horse".toCharArray(), salt, 1000), dek)
        assertArrayEquals(dek, VaultCrypto.unwrapKey(VaultCrypto.pbkdf2("correct horse".toCharArray(), salt, 1000), wrapped))
        try {
            VaultCrypto.unwrapKey(VaultCrypto.pbkdf2("wrong horse".toCharArray(), salt, 1000), wrapped)
            throw AssertionError("wrong password accepted")
        } catch (_: VaultWrongPasswordException) {
        }
    }

    private fun encrypt(dek: ByteArray, id: String, plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = VaultCrypto.encrypt(dek, id.toByteArray(), ByteArrayInputStream(plain), out)
        assertEquals(plain.size.toLong(), size)
        return out.toByteArray()
    }

    private fun decrypt(dek: ByteArray, id: String, sealed: ByteArray): ByteArray =
        VaultCrypto.decryptingStream(dek, id.toByteArray(), ByteArrayInputStream(sealed)).use { it.readBytes() }

    @Test
    fun roundTripsAllChunkBoundaries() {
        val dek = VaultCrypto.randomBytes(32)
        val chunk = VaultCrypto.CHUNK_SIZE
        for (size in listOf(0, 1, 100, chunk - 1, chunk, chunk + 1, 2 * chunk, 3 * chunk + 7)) {
            val plain = Random(size).nextBytes(size)
            val sealed = encrypt(dek, "id-$size", plain)
            assertEquals("encrypted size for $size", VaultCrypto.encryptedSize(size.toLong()), sealed.size.toLong())
            assertArrayEquals("round trip $size", plain, decrypt(dek, "id-$size", sealed))
        }
    }

    @Test(expected = VaultCorruptedException::class)
    fun detectsModifiedCiphertext() {
        val dek = VaultCrypto.randomBytes(32)
        val sealed = encrypt(dek, "a", Random(1).nextBytes(150_000))
        sealed[sealed.size / 2] = (sealed[sealed.size / 2].toInt() xor 1).toByte()
        decrypt(dek, "a", sealed)
    }

    @Test(expected = VaultCorruptedException::class)
    fun detectsTruncationAtChunkBoundary() {
        val dek = VaultCrypto.randomBytes(32)
        val chunk = VaultCrypto.CHUNK_SIZE
        val sealed = encrypt(dek, "a", Random(2).nextBytes(3 * chunk))
        // 只留 header + 前兩段：第二段加密時不是最後一段，解密時會被當成最後一段而驗證失敗
        val header = 13
        decrypt(dek, "a", sealed.copyOf(header + 2 * (chunk + 16)))
    }

    @Test(expected = VaultCorruptedException::class)
    fun rejectsCiphertextFromAnotherEntry() {
        val dek = VaultCrypto.randomBytes(32)
        val sealed = encrypt(dek, "passport.jpg-id", "secret".toByteArray())
        decrypt(dek, "other-id", sealed)
    }

    @Test(expected = VaultCorruptedException::class)
    fun rejectsWrongKey() {
        val sealed = encrypt(VaultCrypto.randomBytes(32), "a", "secret".toByteArray())
        decrypt(VaultCrypto.randomBytes(32), "a", sealed)
    }

    @Test(expected = VaultCorruptedException::class)
    fun rejectsNonVaultData() {
        decrypt(VaultCrypto.randomBytes(32), "a", "not a vault file at all".toByteArray())
    }
}
