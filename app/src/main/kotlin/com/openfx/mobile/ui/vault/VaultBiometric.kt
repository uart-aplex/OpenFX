package com.openfx.mobile.ui.vault

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.openfx.core.vault.VaultStore
import com.openfx.mobile.R
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 指紋 (強生物辨識) 解鎖
 *
 * 啟用時產生一把需要生物辨識才能使用的 Keystore AES 金鑰，用它包裝保險箱的資料金鑰；
 * 解鎖時由使用者按指紋解開 Keystore 金鑰 → 取回資料金鑰。
 * 新增指紋時系統會讓這把金鑰失效，必須用密碼解鎖後重新啟用。
 */
class VaultBiometric(
    private val activity: FragmentActivity,
    private val store: VaultStore
) : VaultBiometricUi {

    override var isEnabled by mutableStateOf(store.config?.biometricWrappedKey != null)
        private set

    /** 裝置有可用的強生物辨識 (TV 與沒設定指紋的手機回傳 false) */
    val isAvailable: Boolean
        get() = BiometricManager.from(activity).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    override fun enable(onDone: (String?) -> Unit) {
        val cipher = try {
            Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, createKey()) }
        } catch (e: Exception) {
            onDone(e.message)
            return
        }
        prompt(cipher, onSuccess = { authenticated ->
            val key = store.exportKeyForBiometric()
            try {
                val sealed = authenticated.doFinal(key)
                store.setBiometricWrappedKey(authenticated.iv + sealed)
                isEnabled = true
                onDone(null)
            } catch (e: Exception) {
                onDone(e.message)
            } finally {
                key.fill(0)
            }
        }, onError = onDone)
    }

    override fun disable() {
        store.setBiometricWrappedKey(null)
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
        isEnabled = false
    }

    override fun unlock(onKey: (ByteArray) -> Unit, onError: (String) -> Unit) {
        val wrapped = store.config?.biometricWrappedKey?.hexToBytes() ?: return
        val secretKey = keyStore().getKey(KEY_ALIAS, null) as? SecretKey
        val cipher = try {
            requireNotNull(secretKey) { "Missing biometric key" }
            Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, wrapped, 0, IV_BYTES))
            }
        } catch (_: KeyPermanentlyInvalidatedException) {
            disable()
            onError(activity.getString(R.string.vault_biometric_invalidated))
            return
        } catch (e: Exception) {
            disable()
            onError(activity.getString(R.string.vault_biometric_invalidated))
            return
        }
        prompt(cipher, onSuccess = { authenticated ->
            try {
                onKey(authenticated.doFinal(wrapped, IV_BYTES, wrapped.size - IV_BYTES))
            } catch (e: Exception) {
                onError(e.message ?: e.javaClass.simpleName)
            }
        }, onError = { message -> message?.let(onError) })
    }

    private fun prompt(cipher: Cipher, onSuccess: (Cipher) -> Unit, onError: (String?) -> Unit) {
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.vault_biometric_prompt))
            .setNegativeButtonText(activity.getString(R.string.cancel))
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                result.cryptoObject?.cipher?.let(onSuccess) ?: onError(null)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // 使用者按取消不顯示錯誤
                val cancelled = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_CANCELED
                onError(if (cancelled) null else errString.toString())
            }
        }).authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun createKey(): SecretKey {
        keyStore().deleteEntry(KEY_ALIAS)
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(spec)
            generateKey()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private companion object {
        const val KEY_ALIAS = "openfx_vault_biometric_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
