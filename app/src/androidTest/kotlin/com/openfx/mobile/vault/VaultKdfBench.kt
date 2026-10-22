package com.openfx.mobile.vault

import com.openfx.core.vault.VaultCrypto
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 量測保險箱金鑰推導在實機上的速度，並確認系統 PBKDF2 與自製實作結果一致 (含中文密碼)
 *
 * am instrument -w -e mode kdf com.openfx.mobile.test/com.openfx.mobile.apps.AppTransferInstrumentation
 */
object VaultKdfBench {
    fun run(): String {
        val salt = ByteArray(16) { it.toByte() }
        val iterations = 200_000
        val out = StringBuilder()

        for (password in listOf("ascii-pass-123", "中文密碼🔐ñ")) {
            val t0 = System.nanoTime()
            val manual = VaultCrypto.pbkdf2(password.toCharArray(), salt, iterations)
            val manualMs = (System.nanoTime() - t0) / 1_000_000

            val t1 = System.nanoTime()
            val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(PBEKeySpec(password.toCharArray(), salt, iterations, 256)).encoded
            val factoryMs = (System.nanoTime() - t1) / 1_000_000

            out.append("  ")
                .append(if (password.all { it.code < 128 }) "ascii" else "unicode")
                .append(": manual=${manualMs}ms factory=${factoryMs}ms same=${manual.contentEquals(factory)}\n")
        }
        val t2 = System.nanoTime()
        val calibrated = VaultCrypto.calibrateIterations()
        val check = System.nanoTime()
        VaultCrypto.pbkdf2("x".toCharArray(), salt, calibrated)
        out.append("  calibrated=$calibrated (calibration ${(check - t2) / 1_000_000}ms, ")
            .append("unlock ≈ ${(System.nanoTime() - check) / 1_000_000}ms)\n")
        return out.toString()
    }
}
