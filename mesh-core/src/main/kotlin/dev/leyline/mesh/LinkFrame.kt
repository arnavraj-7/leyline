package dev.leyline.mesh

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * What travels in one BLE write: the link layer below [Packet].
 *
 * A packet can be up to [PacketCodec.MAX_ENCODED_SIZE] bytes but one GATT write
 * carries at most MTU - 3 bytes, so packets are split into [Data] frames. Every
 * link starts with a [Hello] so each side learns the other's [NodeId]; a GATT
 * server otherwise only sees a rotating Bluetooth address.
 *
 * ```
 * Hello: type=1 u8 | version u8 | node id i32 | [name length u8 | name]   (6 + 1 + n bytes)
 * Data:  type=2 u8 | transfer id u16 | index u8 | count u8 | chunk  (5 + n bytes)
 * ```
 */
sealed interface LinkFrame {
    /** [name] is the person's display name, so neighbours show "Abhay" instead of a hex id. */
    data class Hello(val node: NodeId, val version: Int = LinkFrameCodec.VERSION, val name: String? = null) : LinkFrame

    /** Frame [index] of [count] of transfer [transferId]; transfer ids are per link. */
    class Data(val transferId: Int, val index: Int, val count: Int, val chunk: ByteArray) : LinkFrame {
        override fun equals(other: Any?): Boolean =
            other is Data && transferId == other.transferId && index == other.index &&
                count == other.count && chunk.contentEquals(other.chunk)

        override fun hashCode(): Int = 31 * (31 * transferId + index) + count

        override fun toString(): String = "Data(transfer=$transferId, ${index + 1}/$count, ${chunk.size}B)"
    }
}

object LinkFrameCodec {
    const val VERSION = 1
    const val DATA_HEADER = 5

    /** BLE's default ATT MTU is 23, which leaves 20 bytes per write. Nothing smaller works. */
    const val MIN_FRAME_SIZE = 20

    private const val TYPE_HELLO = 1
    private const val TYPE_DATA = 2
    private const val MAX_FRAMES = 255

    fun encode(frame: LinkFrame): ByteArray = when (frame) {
        is LinkFrame.Hello -> {
            val name = frame.name?.takeIf { it.isNotBlank() }?.let { utf8Prefix(it, MessageBody.MAX_NAME_BYTES) }
            val buf = ByteBuffer.allocate(6 + (name?.let { 1 + it.size } ?: 0))
                .put(TYPE_HELLO.toByte())
                .put(frame.version.toByte())
                .putInt(frame.node.value)
            name?.let { buf.put(it.size.toByte()).put(it) }
            buf.array()
        }
        is LinkFrame.Data -> ByteBuffer.allocate(DATA_HEADER + frame.chunk.size)
            .put(TYPE_DATA.toByte())
            .putShort(frame.transferId.toShort())
            .put(frame.index.toByte())
            .put(frame.count.toByte())
            .put(frame.chunk)
            .array()
    }

    fun decode(bytes: ByteArray): LinkFrame {
        val buf = ByteBuffer.wrap(bytes)
        try {
            return when (val type = buf.get().toInt()) {
                TYPE_HELLO -> {
                    val version = buf.get().toUByte().toInt()
                    val node = NodeId(buf.getInt())
                    val name = if (buf.hasRemaining()) {
                        val length = buf.get().toUByte().toInt()
                        if (length != buf.remaining()) throw MalformedPacketException("hello name of $length in ${buf.remaining()} bytes")
                        ByteArray(length).also { buf.get(it) }.decodeToString()
                    } else {
                        null
                    }
                    LinkFrame.Hello(node, version, name)
                }
                TYPE_DATA -> {
                    val transferId = buf.getShort().toUShort().toInt()
                    val index = buf.get().toUByte().toInt()
                    val count = buf.get().toUByte().toInt()
                    if (count == 0 || index >= count) {
                        throw MalformedPacketException("frame index $index of $count")
                    }
                    val chunk = ByteArray(buf.remaining()).also { buf.get(it) }
                    if (chunk.isEmpty()) throw MalformedPacketException("empty data frame")
                    LinkFrame.Data(transferId, index, count, chunk)
                }
                else -> throw MalformedPacketException("unknown frame type $type")
            }
        } catch (e: BufferUnderflowException) {
            throw MalformedPacketException("truncated frame (${bytes.size} bytes)")
        }
    }

    /**
     * Splits [bytes] into encoded frames that each fit in [frameSize] bytes,
     * where frameSize is the link's negotiated MTU - 3 (capped by policy).
     */
    fun split(bytes: ByteArray, transferId: Int, frameSize: Int): List<ByteArray> {
        require(frameSize >= MIN_FRAME_SIZE) { "frame size $frameSize below $MIN_FRAME_SIZE" }
        require(bytes.isNotEmpty()) { "nothing to send" }
        val chunkSize = frameSize - DATA_HEADER
        val count = (bytes.size + chunkSize - 1) / chunkSize
        require(count <= MAX_FRAMES) { "${bytes.size} bytes needs $count frames, max $MAX_FRAMES" }
        val id = transferId and 0xFFFF
        return List(count) { index ->
            val from = index * chunkSize
            val chunk = bytes.copyOfRange(from, minOf(from + chunkSize, bytes.size))
            encode(LinkFrame.Data(id, index, count, chunk))
        }
    }
}
