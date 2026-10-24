package dev.leyline.app.mesh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.P256
import dev.leyline.mesh.PacketSignatures
import dev.leyline.mesh.PacketSigner
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec

/**
 * This install's identity: a P-256 key pair created inside the Android Keystore.
 * The private key is generated in the phone's secure hardware (TEE) and can't be
 * read out, even by this app; we can only ask the Keystore to sign with it.
 * Uninstalling deletes the key, so a reinstall is a new node.
 */
class KeystoreSigner private constructor(
    private val privateKey: PrivateKey,
    override val publicKey: ByteArray,
) : PacketSigner {
    /** Derived from the key, so nobody can claim it without the private key. */
    val nodeId: NodeId = PacketSignatures.nodeIdFor(publicKey)

    override fun sign(data: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(data)
            sign()
        }
        return P256.derToRaw(der)
    }

    companion object {
        private const val ALIAS = "leyline-node"
        private const val PROVIDER = "AndroidKeyStore"

        fun loadOrCreate(): KeystoreSigner {
            val store = KeyStore.getInstance(PROVIDER).apply { load(null) }
            repeat(3) {
                if (!store.containsAlias(ALIAS)) generate()
                val entry = store.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
                val signer = KeystoreSigner(entry.privateKey, P256.compress(entry.certificate.publicKey as ECPublicKey))
                // A 1-in-4-billion key whose id would mean "every node": make another.
                if (signer.nodeId != NodeId.BROADCAST) return signer
                store.deleteEntry(ALIAS)
            }
            error("could not create a usable node key")
        }

        private fun generate() {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER).run {
                initialize(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                        .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                        .setDigests(KeyProperties.DIGEST_SHA256)
                        .build(),
                )
                generateKeyPair()
            }
        }
    }
}
