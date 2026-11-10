package fr.zapette.tv.engine

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Passwords of the accounts. Each value is encrypted with an AES key that lives in the
 * Android Keystore and cannot be exported, so the file on disk holds no readable password.
 */
class SecretStore(context: Context) {
    private val prefs = context.getSharedPreferences("zapette_secrets", Context.MODE_PRIVATE)

    /** The clear value, or "" when there is none or it cannot be decrypted (then it must be typed again). */
    fun get(name: String): String {
        val stored = prefs.getString(name, null) ?: return ""
        return runCatching { decrypt(Base64.decode(stored, Base64.NO_WRAP)) }.getOrDefault("")
    }

    fun put(name: String, value: String) {
        val editor = prefs.edit()
        if (value.isEmpty()) {
            editor.remove(name)
        } else {
            editor.putString(name, Base64.encodeToString(encrypt(value), Base64.NO_WRAP))
        }
        editor.apply()
    }

    private fun encrypt(clear: String): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(clear.toByteArray(Charsets.UTF_8))
    }

    private fun decrypt(data: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, data.copyOfRange(0, IV_BYTES)))
        return String(cipher.doFinal(data.copyOfRange(IV_BYTES, data.size)), Charsets.UTF_8)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "zapette_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
