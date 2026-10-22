package app.oriel.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.BadPaddingException
import javax.crypto.IllegalBlockSizeException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals small secrets (the sign-in cookie) for storage with AES-256-GCM under a key that
 * never leaves the Android Keystore: a copy of the app's files, from a rooted phone or a
 * backup, is useless without the device's secure hardware.
 *
 * A sealed value is `v1:` followed by base64 of the 12-byte IV and the ciphertext with its tag.
 * [open] returns null for anything it can't authenticate (a key lost to a factory reset of the
 * keystore, an invalidated key, tampering): the caller treats that as signed out. A keystore that
 * can't answer right now (busy, or a vendor's flaky one just after boot) is [Unavailable] instead,
 * which mustn't cost the session.
 */
class SecretBox(private val key: () -> SecretKey) {
    /** [plain] sealed for storage; throws if the keystore can't, and then nothing should be stored. */
    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        // The keystore picks the IV itself (randomized encryption); it's read back to store with the text.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    /** The keystore couldn't be asked just now; try again later rather than forget the secret. */
    class Unavailable(cause: Throwable) : Exception(cause)

    /** [stored] opened; null if it can never be; throws [Unavailable] if it can't be right now. */
    fun open(stored: String): String? {
        if (!isSealed(stored)) return null
        return try {
            val bytes = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (bytes.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            // Bad base64, a failed tag check, a key the keystore no longer has or has invalidated:
            // gone for good. Anything else is the keystore not answering, which may pass.
            if (e is IllegalArgumentException || e is BadPaddingException || e is IllegalBlockSizeException ||
                e is KeyPermanentlyInvalidatedException || e is UnrecoverableKeyException
            ) null else throw Unavailable(e)
        }
    }

    companion object {
        private const val PREFIX = "v1:"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KEYSTORE = "AndroidKeyStore"

        fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

        /** A box whose key lives in the Android Keystore under [alias], made the first time it's needed. */
        fun keystore(alias: String): SecretBox = SecretBox { keystoreKey(alias) }

        private fun keystoreKey(alias: String): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getKey(alias, null) as? SecretKey)?.let { return it }
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).apply { init(spec) }.generateKey()
        }
    }
}
