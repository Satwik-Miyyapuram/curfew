package dev.curfew.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The passphrase for the encrypted database.
 *
 * A random passphrase is generated once and stored on disk sealed with an AES key that lives in the
 * Android keystore and cannot be exported from it. That means the database file is useless on its
 * own — copied off the device by adb, by a backup, or by anything else that can read app storage —
 * while Curfew itself needs no password from the user to start after a reboot, which it must not,
 * because enforcement has to resume before anyone unlocks anything.
 */
object DatabaseKey {

    private const val KEY_ALIAS = "curfew.db"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun passphrase(context: Context): ByteArray {
        val sealed = File(context.filesDir, "db.key")
        return if (sealed.exists()) open(sealed.readBytes()) else create(sealed)
    }

    private fun create(sealed: File): ByteArray {
        val passphrase = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = cipher.doFinal(passphrase)
        sealed.parentFile?.mkdirs()
        sealed.writeBytes(cipher.iv + body)
        return passphrase
    }

    private fun open(bytes: ByteArray): ByteArray {
        val iv = bytes.copyOfRange(0, IV_BYTES)
        val body = bytes.copyOfRange(IV_BYTES, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        }
        return cipher.doFinal(body)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Deliberately not `setUserAuthenticationRequired`: enforcement must survive a
                // reboot without anyone unlocking the device first.
                .build(),
        )
        return generator.generateKey()
    }
}
