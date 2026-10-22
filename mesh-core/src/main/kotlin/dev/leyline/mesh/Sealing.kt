package dev.leyline.mesh

import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.PrivateKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * End-to-end encryption for private messages, so relays carry them without
 * being able to read them. Signing (see [PacketSignatures]) already proves who
 * sent a packet; this hides what it says.
 *
 * Each node has a second P-256 key pair just for this ("box key"); its public
 * half travels in that node's signed messages, so it can't be swapped by a relay.
 *
 * ```
 * shared = ECDH(our private box key, their public box key)          32 bytes
 * key    = HKDF-SHA256(salt = nonce, ikm = shared, info = DOMAIN)   32 bytes
 * sealed = nonce (12) | AES-256-GCM(key, nonce, plaintext, aad = DOMAIN | origin | destination)
 * ```
 *
 * The random nonce also salts the key, so every message has its own key even
 * between the same two phones. Static keys mean no forward secrecy: someone who
 * later steals a phone's box key can read old messages they recorded.
 */
object Sealing {
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128

    /** Nonce + GCM tag: what sealing adds to the plaintext. */
    const val OVERHEAD = NONCE_SIZE + TAG_BITS / 8
    private val DOMAIN = "leyline/dm/v1".encodeToByteArray()
    private val random = SecureRandom()

    fun seal(plain: ByteArray, ours: PrivateKey, theirs: ByteArray, origin: NodeId, destination: NodeId): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, ours, theirs, nonce, origin, destination)
        return nonce + cipher.doFinal(plain)
    }

    /** Null if the message wasn't sealed for us by that key, or was changed on the way. */
    fun open(sealed: ByteArray, ours: PrivateKey, theirs: ByteArray, origin: NodeId, destination: NodeId): ByteArray? = try {
        require(sealed.size >= OVERHEAD) { "sealed message too short" }
        val nonce = sealed.copyOfRange(0, NONCE_SIZE)
        cipher(Cipher.DECRYPT_MODE, ours, theirs, nonce, origin, destination).doFinal(sealed, NONCE_SIZE, sealed.size - NONCE_SIZE)
    } catch (e: Exception) {
        null
    }

    fun generate(): KeyPair = P256.generate()

    private fun cipher(mode: Int, ours: PrivateKey, theirs: ByteArray, nonce: ByteArray, origin: NodeId, destination: NodeId): Cipher {
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(ours)
            doPhase(P256.decompress(theirs), true)
            generateSecret()
        }
        val key = hkdf(salt = nonce, ikm = shared, info = DOMAIN)
        val aad = ByteBuffer.allocate(DOMAIN.size + 8).put(DOMAIN).putInt(origin.value).putInt(destination.value).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
    }

    /** RFC 5869 with SHA-256, one output block (32 bytes). */
    private fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(ikm)
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            update(info)
            doFinal(byteArrayOf(1))
        }
    }
}
