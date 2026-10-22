package dev.leyline.mesh

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

class MalformedPacketException(message: String) : Exception(message)

/**
 * Binary wire format, version 2. All integers big-endian. Version 2 added the
 * optional signature block (flag bit 1).
 *
 * ```
 * offset  size  field
 *  0      1     version (2)
 *  1      1     type (1 = MESSAGE, 2 = ACK)
 *  2      1     flags (bit 0 = SOS, bit 1 = signed)
 *  3      1     ttl
 *  4      8     message id
 * 12      4     origin node id
 * 16      4     destination node id (FFFFFFFF = broadcast)
 * 20      8     created at, epoch milliseconds
 * 28      1     path length n
 * 29      4n    relay node ids, in order
 * 29+4n   2     payload length L
 * 31+4n   L     payload
 * if signed:
 *         33    origin public key, compressed P-256
 *         64    ECDSA-SHA256 signature, raw r‖s
 * ```
 */
object PacketCodec {
    const val VERSION = 2
    private const val FLAG_SOS = 0x01
    private const val FLAG_SIGNED = 0x02
    private const val FIXED_HEADER = 31

    /** Largest valid packet: full path, full payload, signed. */
    const val MAX_ENCODED_SIZE = FIXED_HEADER + 4 * Packet.MAX_TTL + Packet.MAX_PAYLOAD + PacketAuth.SIZE

    fun encodedSize(packet: Packet): Int =
        FIXED_HEADER + 4 * packet.path.size + packet.payload.size + (if (packet.auth != null) PacketAuth.SIZE else 0)

    fun encode(packet: Packet): ByteArray {
        val buf = ByteBuffer.allocate(encodedSize(packet))
        buf.put(VERSION.toByte())
        buf.put(packet.type.code.toByte())
        val sos = if (packet.priority == Priority.SOS) FLAG_SOS else 0
        val signed = if (packet.auth != null) FLAG_SIGNED else 0
        buf.put((sos or signed).toByte())
        buf.put(packet.ttl.toByte())
        buf.putLong(packet.id.value)
        buf.putInt(packet.origin.value)
        buf.putInt(packet.destination.value)
        buf.putLong(packet.createdAt)
        buf.put(packet.path.size.toByte())
        packet.path.forEach { buf.putInt(it.value) }
        buf.putShort(packet.payload.size.toShort())
        buf.put(packet.payload)
        packet.auth?.let {
            buf.put(it.publicKey)
            buf.put(it.signature)
        }
        return buf.array()
    }

    /** Bytes come from a radio, so every field is validated rather than trusted. */
    fun decode(bytes: ByteArray): Packet {
        val buf = ByteBuffer.wrap(bytes)
        try {
            val version = buf.get().toUByte().toInt()
            if (version != VERSION) throw MalformedPacketException("unsupported version $version")
            val typeCode = buf.get().toUByte().toInt()
            val type = PacketType.fromCode(typeCode)
                ?: throw MalformedPacketException("unknown type $typeCode")
            val flags = buf.get().toInt()
            val ttl = buf.get().toUByte().toInt()
            val id = MessageId(buf.getLong())
            val origin = NodeId(buf.getInt())
            val destination = NodeId(buf.getInt())
            val createdAt = buf.getLong()
            val pathSize = buf.get().toUByte().toInt()
            if (pathSize > Packet.MAX_TTL) throw MalformedPacketException("path length $pathSize")
            val path = List(pathSize) { NodeId(buf.getInt()) }
            val payloadSize = buf.getShort().toUShort().toInt()
            val signed = flags and FLAG_SIGNED != 0
            val expected = payloadSize + if (signed) PacketAuth.SIZE else 0
            if (expected != buf.remaining()) {
                throw MalformedPacketException("expected $expected bytes after the header but ${buf.remaining()} left")
            }
            val payload = ByteArray(payloadSize).also { buf.get(it) }
            val auth = if (signed) {
                val key = ByteArray(P256.PUBLIC_KEY_SIZE).also { buf.get(it) }
                PacketAuth(key, ByteArray(P256.SIGNATURE_SIZE).also { buf.get(it) })
            } else {
                null
            }
            return Packet(
                id = id,
                type = type,
                priority = if (flags and FLAG_SOS != 0) Priority.SOS else Priority.NORMAL,
                origin = origin,
                destination = destination,
                ttl = ttl,
                createdAt = createdAt,
                path = path,
                payload = payload,
                auth = auth,
            )
        } catch (e: BufferUnderflowException) {
            throw MalformedPacketException("truncated packet (${bytes.size} bytes)")
        } catch (e: IllegalArgumentException) {
            throw MalformedPacketException(e.message ?: "invalid field")
        }
    }
}
