package dev.leyline.app.mesh

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import dev.leyline.mesh.P256
import dev.leyline.mesh.Sealing
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This install's encryption key pair for private messages (see [Sealing]).
 *
 * Why not keep it in the Keystore like the signing key: Keystore key agreement
 * (ECDH) needs Android 12, and our oldest phone runs Android 9. So the key pair
 * is made in software and its private half is stored encrypted under an AES key
 * that does live in the Keystore. A copy of the app's files is useless without
 * the phone's secure hardware.
 */
class BoxKeys private constructor(val private: PrivateKey, val publicKey: ByteArray) {
    companion object {
        private const val PREFS = "leyline-box"
        private const val KEY_PRIVATE = "private"
        private const val KEY_PUBLIC = "public"
        private const val KEY_IV = "iv"
        private const val WRAP_ALIAS = "leyline-box-wrap"
        private const val PROVIDER = "AndroidKeyStore"

        fun loadOrCreate(context: Context): BoxKeys {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val wrap = wrappingKey()
            val stored = runCatching {
                val iv = decode(prefs.getString(KEY_IV, null)) ?: return@runCatching null
                val wrapped = decode(prefs.getString(KEY_PRIVATE, null)) ?: return@runCatching null
                val public = decode(prefs.getString(KEY_PUBLIC, null)) ?: return@runCatching null
                val pkcs8 = Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, wrap, GCMParameterSpec(128, iv))
                    doFinal(wrapped)
                }
                val factory = KeyFactory.getInstance("EC")
                val publicKey = factory.generatePublic(X509EncodedKeySpec(public)) as ECPublicKey
                BoxKeys(factory.generatePrivate(PKCS8EncodedKeySpec(pkcs8)), P256.compress(publicKey))
            }.getOrNull()
            if (stored != null) return stored

            val pair = Sealing.generate()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, wrap) }
            val wrapped = cipher.doFinal(pair.private.encoded)
            prefs.edit {
                putString(KEY_IV, encode(cipher.iv))
                putString(KEY_PRIVATE, encode(wrapped))
                putString(KEY_PUBLIC, encode(pair.public.encoded))
            }
            return BoxKeys(pair.private, P256.compress(pair.public as ECPublicKey))
        }

        private fun wrappingKey(): SecretKey {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            (store.getEntry(WRAP_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
                init(
                    KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build(),
                )
                generateKey()
            }
        }

        private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

        private fun decode(value: String?): ByteArray? = value?.let { Base64.decode(it, Base64.NO_WRAP) }
    }
}
