package dev.leyline.mesh

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/**
 * Signs the packets this node originates. On a phone the private key is created
 * inside the Android Keystore and never leaves it; tests and the simulator use
 * [SoftwareSigner].
 */
interface PacketSigner {
    /** 33-byte compressed P-256 public key. */
    val publicKey: ByteArray

    /** 64-byte raw r‖s ECDSA-SHA256 signature over [data]. */
    fun sign(data: ByteArray): ByteArray
}

/** The origin's public key and its signature, carried at the end of a signed packet. */
class PacketAuth(val publicKey: ByteArray, val signature: ByteArray) {
    init {
        require(publicKey.size == P256.PUBLIC_KEY_SIZE) { "public key must be ${P256.PUBLIC_KEY_SIZE} bytes" }
        require(signature.size == P256.SIGNATURE_SIZE) { "signature must be ${P256.SIGNATURE_SIZE} bytes" }
    }

    override fun equals(other: Any?): Boolean =
        other is PacketAuth && publicKey.contentEquals(other.publicKey) && signature.contentEquals(other.signature)

    override fun hashCode(): Int = publicKey.contentHashCode()

    companion object {
        const val SIZE = P256.PUBLIC_KEY_SIZE + P256.SIGNATURE_SIZE
    }
}

enum class Verdict {
    VALID,
    UNSIGNED,

    /** The key doesn't hash to the packet's origin: someone claiming to be another node. */
    WRONG_ORIGIN,

    /** Signed content was changed on the way, or the signature is garbage. */
    BAD_SIGNATURE,
}

/**
 * What a signature covers, and the link between keys and node ids.
 *
 * Relays must lower the TTL and append themselves to the path, so those two
 * fields are left out; everything the origin wrote is covered. A relay can
 * therefore still tamper with TTL (bounded by [Packet.MAX_TTL]) or the path,
 * but never with who sent a message or what it says.
 *
 * A node id is the first 4 bytes of SHA-256(public key), so an id can't be
 * claimed without the matching private key. 32 bits alone is brute-forceable
 * offline, which is why receivers also pin the first key they see per id
 * ([KeyPins]): a later collision key for a known id is rejected.
 */
object PacketSignatures {
    private val DOMAIN = "leyline/packet/v2".encodeToByteArray()

    fun nodeIdFor(publicKey: ByteArray): NodeId =
        NodeId(ByteBuffer.wrap(MessageDigest.getInstance("SHA-256").digest(publicKey)).getInt())

    fun signedBytes(packet: Packet, publicKey: ByteArray): ByteArray =
        ByteBuffer.allocate(DOMAIN.size + 26 + 2 + packet.payload.size + publicKey.size)
            .put(DOMAIN)
            .put(packet.type.code.toByte())
            .put((if (packet.priority == Priority.SOS) 1 else 0).toByte())
            .putLong(packet.id.value)
            .putInt(packet.origin.value)
            .putInt(packet.destination.value)
            .putLong(packet.createdAt)
            .putShort(packet.payload.size.toShort())
            .put(packet.payload)
            .put(publicKey)
            .array()

    fun sign(packet: Packet, signer: PacketSigner): Packet {
        val key = signer.publicKey
        return packet.copy(auth = PacketAuth(key, signer.sign(signedBytes(packet, key))))
    }

    fun verify(packet: Packet): Verdict {
        val auth = packet.auth ?: return Verdict.UNSIGNED
        if (nodeIdFor(auth.publicKey) != packet.origin) return Verdict.WRONG_ORIGIN
        val ok = P256.verify(auth.publicKey, signedBytes(packet, auth.publicKey), auth.signature)
        return if (ok) Verdict.VALID else Verdict.BAD_SIGNATURE
    }
}

/**
 * Trust on first use: the first key seen for a node id is the only one accepted
 * for it afterwards. Since ids derive from keys, a reinstall gets a new id, so a
 * different key for a known id is never legitimate.
 */
interface KeyPins {
    /** True if [publicKey] is the one pinned for [node], pinning it if [node] is new. */
    suspend fun check(node: NodeId, publicKey: ByteArray): Boolean
}

class InMemoryKeyPins : KeyPins {
    private val pins = HashMap<NodeId, ByteArray>()

    override suspend fun check(node: NodeId, publicKey: ByteArray): Boolean = synchronized(pins) {
        pins.getOrPut(node) { publicKey.copyOf() }.contentEquals(publicKey)
    }
}

/** Key pair held in memory, for tests and the simulator. */
class SoftwareSigner(private val keyPair: KeyPair = P256.generate()) : PacketSigner {
    override val publicKey: ByteArray = P256.compress(keyPair.public as ECPublicKey)
    val nodeId: NodeId = PacketSignatures.nodeIdFor(publicKey)

    override fun sign(data: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(data)
            sign()
        }
        return P256.derToRaw(der)
    }
}

/**
 * ECDSA on NIST P-256 (secp256r1) using only java.security, so it runs the same on
 * the JVM and on Android 8+, where the Keystore supports it in hardware. Ed25519
 * would be smaller but needs Android 13+ there.
 *
 * Wire formats are fixed-size to keep packets small: a compressed public key (33
 * bytes: parity prefix + x) and a raw signature (r‖s, 64 bytes) instead of DER.
 */
object P256 {
    const val PUBLIC_KEY_SIZE = 33
    const val SIGNATURE_SIZE = 64
    private const val COORD = 32

    private val params: ECParameterSpec =
        AlgorithmParameters.getInstance("EC").run {
            init(ECGenParameterSpec("secp256r1"))
            getParameterSpec(ECParameterSpec::class.java)
        }
    private val p: BigInteger = (params.curve.field as java.security.spec.ECFieldFp).p
    private val a: BigInteger = params.curve.a
    private val b: BigInteger = params.curve.b

    fun generate(): KeyPair =
        KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

    fun compress(key: ECPublicKey): ByteArray {
        val point = key.w
        val prefix = if (point.affineY.testBit(0)) 0x03 else 0x02
        return byteArrayOf(prefix.toByte()) + unsigned(point.affineX)
    }

    /** Rebuilds the full key from x: y² = x³ + ax + b, and P-256's p ≡ 3 (mod 4) gives y = rhs^((p+1)/4). */
    fun decompress(bytes: ByteArray): ECPublicKey {
        require(bytes.size == PUBLIC_KEY_SIZE) { "compressed key must be $PUBLIC_KEY_SIZE bytes" }
        val prefix = bytes[0].toInt()
        require(prefix == 0x02 || prefix == 0x03) { "bad key prefix $prefix" }
        val x = BigInteger(1, bytes.copyOfRange(1, PUBLIC_KEY_SIZE))
        require(x < p) { "x outside the field" }
        val rhs = x.pow(3).add(a.multiply(x)).add(b).mod(p)
        var y = rhs.modPow(p.add(BigInteger.ONE).shiftRight(2), p)
        require(y.multiply(y).mod(p) == rhs) { "point is not on the curve" }
        if (y.testBit(0) != (prefix == 0x03)) y = p.subtract(y)
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }

    /** False for anything invalid, never an exception: these bytes come off the radio. */
    fun verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(decompress(publicKey))
            update(data)
            verify(rawToDer(signature))
        }
    } catch (e: Exception) {
        false
    }

    /** DER `SEQUENCE { INTEGER r, INTEGER s }`, as Java and the Keystore produce, to fixed 64 bytes. */
    fun derToRaw(der: ByteArray): ByteArray {
        val buf = ByteBuffer.wrap(der)
        require(buf.get().toInt() == 0x30) { "not a DER sequence" }
        readLength(buf)
        val r = readInteger(buf)
        val s = readInteger(buf)
        require(!buf.hasRemaining()) { "trailing bytes after signature" }
        return unsigned(r) + unsigned(s)
    }

    fun rawToDer(raw: ByteArray): ByteArray {
        require(raw.size == SIGNATURE_SIZE) { "raw signature must be $SIGNATURE_SIZE bytes" }
        // toByteArray is minimal two's complement, so it adds the 0x00 DER needs before a high bit.
        val r = BigInteger(1, raw.copyOfRange(0, COORD)).toByteArray()
        val s = BigInteger(1, raw.copyOfRange(COORD, SIGNATURE_SIZE)).toByteArray()
        val body = byteArrayOf(0x02, r.size.toByte()) + r + byteArrayOf(0x02, s.size.toByte()) + s
        return byteArrayOf(0x30, body.size.toByte()) + body // at most 70 bytes: short-form length
    }

    private fun readLength(buf: ByteBuffer): Int {
        val first = buf.get().toInt() and 0xFF
        return when {
            first < 0x80 -> first
            first == 0x81 -> buf.get().toInt() and 0xFF
            else -> throw IllegalArgumentException("unexpected DER length")
        }
    }

    private fun readInteger(buf: ByteBuffer): BigInteger {
        require(buf.get().toInt() == 0x02) { "expected DER integer" }
        val bytes = ByteArray(readLength(buf)).also { buf.get(it) }
        val value = BigInteger(bytes)
        require(value.signum() > 0 && value.bitLength() <= COORD * 8) { "integer out of range" }
        return value
    }

    /** Big-endian, exactly 32 bytes. */
    private fun unsigned(value: BigInteger): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == COORD -> bytes
            bytes.size > COORD -> bytes.copyOfRange(bytes.size - COORD, bytes.size) // drop the sign byte
            else -> ByteArray(COORD - bytes.size) + bytes
        }
    }
}
