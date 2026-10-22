package com.openfx.core.vault

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 密碼錯誤 (包裝的資料金鑰解不開) */
class VaultWrongPasswordException : IOException("Wrong vault password")

/** 加密資料被竄改、截斷或與項目不符 */
class VaultCorruptedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 保險箱加密原語 — 純 JVM，方便單元測試
 *
 * - 主密碼 → PBKDF2-HMAC-SHA256 → 金鑰加密金鑰 (KEK)
 * - 隨機 256-bit 資料金鑰 (DEK) 以 KEK 做 AES-GCM 包裝；改密碼只需重新包裝 DEK
 * - 檔案內容以 DEK 分段 AES-GCM 加密 (見 [encrypt]/[decryptingStream])
 */
object VaultCrypto {

    const val KEY_BYTES = 32
    const val SALT_BYTES = 16
    const val DEFAULT_ITERATIONS = 200_000

    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val TAG_BYTES = GCM_TAG_BITS / 8

    /** 分段大小：每段明文 64 KiB，大檔案不必整個載入記憶體 */
    const val CHUNK_SIZE = 64 * 1024

    private val MAGIC = byteArrayOf('O'.code.toByte(), 'F'.code.toByte(), 'X'.code.toByte(), 'V'.code.toByte())
    private const val FORMAT_VERSION: Byte = 1
    private const val NONCE_PREFIX_BYTES = 8
    private const val HEADER_BYTES = 4 + 1 + NONCE_PREFIX_BYTES

    private val random = SecureRandom()

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 600_000

    /**
     * 依目前裝置速度挑選迭代次數，讓解鎖大約花 [targetMillis]
     *
     * 手機可以用更多次數 (更難暴力破解)；慢的 TV 盒子至少用 [MIN_ITERATIONS]，避免太弱。
     * 次數記錄在各保險箱的設定檔，換裝置還原也能正確解鎖。
     */
    fun calibrateIterations(targetMillis: Long = 1_000): Int {
        val sample = 20_000
        val salt = randomBytes(SALT_BYTES)
        val start = System.nanoTime()
        pbkdf2("calibration".toCharArray(), salt, sample)
        val elapsedMs = ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
        val estimate = sample.toLong() * targetMillis / elapsedMs
        return estimate.coerceIn(MIN_ITERATIONS.toLong(), MAX_ITERATIONS.toLong()).toInt()
    }

    // ===== 金鑰 =====

    /**
     * PBKDF2-HMAC-SHA256 (RFC 8018)。自己實作而不用 SecretKeyFactory，
     * 因為 Android 7.x 沒有 PBKDF2WithHmacSHA256，而且這樣可以用 JVM 單元測試驗證。
     */
    fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, keyBytes: Int = KEY_BYTES): ByteArray {
        require(iterations > 0 && keyBytes > 0)
        val passwordBytes = String(password).toByteArray(Charsets.UTF_8)
        // HMAC 不接受空 key；空密碼改用 1 byte 0 — 與標準 PBKDF2 對空密碼的結果一致 (HMAC 會把短 key 補 0)
        val macKey = if (passwordBytes.isEmpty()) ByteArray(1) else passwordBytes
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }
        passwordBytes.fill(0)

        val hashLen = mac.macLength
        val blocks = (keyBytes + hashLen - 1) / hashLen
        val output = ByteArray(blocks * hashLen)
        val u = ByteArray(hashLen)
        val t = ByteArray(hashLen)
        for (block in 1..blocks) {
            mac.update(salt)
            mac.update(ByteBuffer.allocate(4).putInt(block).array())
            mac.doFinal(u, 0)
            u.copyInto(t)
            for (i in 2..iterations) {
                mac.update(u)
                mac.doFinal(u, 0)
                for (j in 0 until hashLen) t[j] = (t[j].toInt() xor u[j].toInt()).toByte()
            }
            t.copyInto(output, (block - 1) * hashLen)
        }
        u.fill(0)
        t.fill(0)
        return output.copyOf(keyBytes).also { output.fill(0) }
    }

    /** 用 KEK 包裝 DEK：IV(12) + AES-GCM(DEK) */
    fun wrapKey(kek: ByteArray, dek: ByteArray, aad: ByteArray = WRAP_AAD): ByteArray {
        val iv = randomBytes(GCM_IV_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        return iv + cipher.doFinal(dek)
    }

    /** 解開包裝的 DEK；KEK 錯誤 (密碼錯) 時丟 [VaultWrongPasswordException] */
    fun unwrapKey(kek: ByteArray, wrapped: ByteArray, aad: ByteArray = WRAP_AAD): ByteArray {
        if (wrapped.size <= GCM_IV_BYTES + TAG_BYTES) throw VaultCorruptedException("Wrapped key too short")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(kek, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, wrapped, 0, GCM_IV_BYTES)
        )
        cipher.updateAAD(aad)
        return try {
            cipher.doFinal(wrapped, GCM_IV_BYTES, wrapped.size - GCM_IV_BYTES)
        } catch (_: AEADBadTagException) {
            throw VaultWrongPasswordException()
        }
    }

    private val WRAP_AAD = "openfx-vault-dek-v1".toByteArray()

    // ===== 分段串流加密 =====

    /**
     * 加密 [input] 寫到 [output]，回傳明文長度
     *
     * 格式：MAGIC(4) | version(1) | noncePrefix(8) | 各段 [AES-GCM(明文段) + tag(16)]…
     * - 每段 nonce = noncePrefix(8) || 段序號(4, big-endian)
     * - AAD = header || [associated] || lastFlag(1)：後面沒有資料的那一段 flag = 1，偵測截斷；
     *   [associated] 綁定項目 id，避免把 A 檔的密文搬到 B 檔
     * - 空檔案也會有一段 (只有 tag)，所以一定存在 lastFlag = 1 的段
     */
    fun encrypt(dek: ByteArray, associated: ByteArray, input: InputStream, output: OutputStream): Long {
        val prefix = randomBytes(NONCE_PREFIX_BYTES)
        val header = MAGIC + byteArrayOf(FORMAT_VERSION) + prefix
        output.write(header)

        val key = SecretKeySpec(dek, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val current = ByteArray(CHUNK_SIZE)
        val next = ByteArray(CHUNK_SIZE)
        var currentLength = input.readFully(current)
        var counter = 0
        var total = 0L

        while (true) {
            // 往後多讀一段，才知道目前這段是不是最後一段 (解密端用「後面是否還有資料」判斷)
            val nextLength = if (currentLength == CHUNK_SIZE) input.readFully(next) else 0
            val last = nextLength == 0
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce(prefix, counter)))
            cipher.updateAAD(aad(header, associated, last))
            output.write(cipher.doFinal(current, 0, currentLength))
            total += currentLength
            if (last) break
            counter++
            if (counter < 0) throw IOException("File too large")
            next.copyInto(current, 0, 0, nextLength)
            currentLength = nextLength
        }
        current.fill(0)
        next.fill(0)
        return total
    }

    /**
     * 回傳解密中的 InputStream；讀到被竄改/截斷的資料時丟 [VaultCorruptedException]
     */
    fun decryptingStream(dek: ByteArray, associated: ByteArray, input: InputStream): InputStream =
        DecryptingStream(dek, associated, input)

    /** 加密後的大小 (用來顯示/檢查) */
    fun encryptedSize(plainSize: Long): Long {
        val chunks = maxOf(1L, (plainSize + CHUNK_SIZE - 1) / CHUNK_SIZE)
        return HEADER_BYTES + plainSize + chunks * TAG_BYTES
    }

    private fun nonce(prefix: ByteArray, counter: Int): ByteArray =
        prefix + ByteBuffer.allocate(4).putInt(counter).array()

    private fun aad(header: ByteArray, associated: ByteArray, last: Boolean): ByteArray =
        header + associated + byteArrayOf(if (last) 1 else 0)

    /** 盡量讀滿 buffer，回傳實際讀到的位元組數 (EOF 時可能小於 buffer 大小) */
    private fun InputStream.readFully(buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val read = read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        return total
    }

    private class DecryptingStream(
        dek: ByteArray,
        private val associated: ByteArray,
        private val source: InputStream
    ) : InputStream() {
        private val key = SecretKeySpec(dek.copyOf(), "AES")
        private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        private val header = ByteArray(HEADER_BYTES)
        private val prefix: ByteArray
        private val sealed = ByteArray(CHUNK_SIZE + TAG_BYTES)
        private val lookahead = ByteArray(1)
        private var hasLookahead = false
        private var plain = ByteArray(0)
        private var plainPos = 0
        private var counter = 0
        private var finished = false

        init {
            if (source.readFully(header) != HEADER_BYTES) throw VaultCorruptedException("Header too short")
            if (!header.copyOfRange(0, 4).contentEquals(MAGIC)) throw VaultCorruptedException("Not a vault file")
            if (header[4] != FORMAT_VERSION) throw VaultCorruptedException("Unsupported vault format ${header[4]}")
            prefix = header.copyOfRange(5, HEADER_BYTES)
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (plainPos >= plain.size) {
                if (finished) return -1
                nextChunk()
            }
            val count = minOf(len, plain.size - plainPos)
            plain.copyInto(b, off, plainPos, plainPos + count)
            plainPos += count
            return count
        }

        private fun nextChunk() {
            // 先把上次為了判斷 EOF 多讀的 1 byte 放回段落開頭
            var length = 0
            if (hasLookahead) {
                sealed[0] = lookahead[0]
                length = 1
                hasLookahead = false
            }
            while (length < sealed.size) {
                val read = source.read(sealed, length, sealed.size - length)
                if (read < 0) break
                length += read
            }
            if (length < TAG_BYTES) {
                throw VaultCorruptedException("Truncated vault file", EOFException())
            }
            // 段落滿了要再看下一個 byte，才知道這是不是最後一段
            val last = if (length == sealed.size) {
                val peek = source.read(lookahead, 0, 1)
                if (peek == 1) {
                    hasLookahead = true
                    false
                } else true
            } else true

            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, nonce(prefix, counter)))
            cipher.updateAAD(aad(header, associated, last))
            plain = try {
                cipher.doFinal(sealed, 0, length)
            } catch (e: AEADBadTagException) {
                throw VaultCorruptedException("Vault data was modified or truncated", e)
            }
            plainPos = 0
            counter++
            if (last) finished = true
        }

        override fun close() {
            plain.fill(0)
            source.close()
        }
    }
}
