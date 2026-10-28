package dev.leyline.mesh

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * GPS fix attached to a message. Degrees are stored as integers scaled by 1e7
 * (about 1 cm of precision), which is exact and half the size of two doubles.
 */
data class Location(val latE7: Int, val lonE7: Int, val accuracyMeters: Int) {
    val latitude: Double get() = latE7 / 1e7
    val longitude: Double get() = lonE7 / 1e7

    companion object {
        fun of(latitude: Double, longitude: Double, accuracyMeters: Float): Location = Location(
            latE7 = Math.round(latitude * 1e7).toInt(),
            lonE7 = Math.round(longitude * 1e7).toInt(),
            accuracyMeters = accuracyMeters.toInt().coerceIn(0, 0xFFFF),
        )
    }
}

/** What an SOS is about, so rescuers can sort by urgency instead of reading every message. */
enum class SosKind(val code: Int) {
    GENERAL(0),
    MEDICAL(1),
    TRAPPED(2),
    FIRE(3),
    SUPPLIES(4);

    companion object {
        fun fromCode(code: Int): SosKind = entries.firstOrNull { it.code == code } ?: GENERAL
    }
}

/** Triage facts attached to an SOS. [people] is how many need help, 0 if not given. */
data class SosDetails(val kind: SosKind, val people: Int = 0, val injured: Boolean = false) {
    init {
        require(people in 0..255) { "people $people outside 0..255" }
    }
}

enum class ResponseStatus(val code: Int) {
    /** "I'm coming": the responder is heading to the SOS. */
    ON_THE_WAY(1),

    /** The responder reached the person; others can stand down. */
    FOUND(2);

    companion object {
        fun fromCode(code: Int): ResponseStatus? = entries.firstOrNull { it.code == code }
    }
}

/** A rescuer's answer to the SOS [sos], broadcast so the victim and every other rescuer see it. */
data class Response(val sos: MessageId, val status: ResponseStatus)

/** What a [MediaChunk] is part of. */
enum class MediaKind(val code: Int) {
    PHOTO(1),
    VOICE(2);

    companion object {
        fun fromCode(code: Int): MediaKind? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One piece of a photo or voice clip. Media is bigger than a packet, so it goes
 * as [count] messages sharing [id]; the receiver shows it once every [index]
 * has arrived. Each piece is a normal signed packet, so relaying, dedup and
 * store-and-forward need nothing new.
 */
class MediaChunk(val id: Long, val kind: MediaKind, val index: Int, val count: Int, val bytes: ByteArray) {
    init {
        require(count in 1..MAX_CHUNKS && index in 0 until count) { "chunk $index of $count" }
        require(bytes.size <= MAX_BYTES) { "chunk of ${bytes.size} bytes" }
    }

    override fun equals(other: Any?) = other is MediaChunk && id == other.id && kind == other.kind &&
        index == other.index && count == other.count && bytes.contentEquals(other.bytes)

    override fun hashCode() = (id * 31 + index).hashCode()

    companion object {
        /** Leaves room in a packet for the name, the encryption key and sealing. */
        const val MAX_BYTES = 900
        const val MAX_CHUNKS = 64
    }
}

/**
 * Payload of a [PacketType.MESSAGE].
 *
 * ```
 * flags u8 (bit 0 location, 1 sender name, 2 SOS details, 3 response, 4 ring, 5 box key, 6 sealed, 7 more flags)
 * [more flags u8 (bit 0 presence, bit 1 media)] if bit 7
 * [lat i32 | lon i32 | accuracy u16]          if bit 0
 * [name length u8 | name UTF-8]               if bit 1
 * [kind u8 | people u8 | details flags u8]    if bit 2; details bit 0 = someone injured
 * [answered SOS id u64 | status u8]           if bit 3
 * [SOS id u64]                                if bit 4
 * [kind u8 | media id u64 | index u8 | count u8 | length u16 | bytes]  if media
 * [sender's box public key, 33 B]             if bit 5
 * text UTF-8, rest of the payload             if bit 6 is clear
 * sealed body, rest of the payload            if bit 6 is set
 * ```
 *
 * [ring] asks the phone that sent SOS [ring] to sound an alarm, so a rescuer in
 * Bluetooth range can find it by ear. The receiver honours it only for its own
 * live SOS, so it can't be used to set off anyone's phone.
 *
 * [boxKey] is the sender's public encryption key (see [Sealing]). It rides in
 * the signed packet, so a relay can't replace it with its own. [sealed] is a
 * whole inner MessageBody encrypted for the destination; [text] is then empty.
 *
 * [presence] marks a "here I am" beacon: just a location and a name, sent every
 * minute so the map can place phones by real direction and distance. It is a
 * flag, not a packet type, so relays need nothing new; receivers don't show it as chat.
 *
 * [media] is one piece of a photo or voice clip, see [MediaChunk].
 */
data class MessageBody(
    val text: String,
    val location: Location? = null,
    val senderName: String? = null,
    val details: SosDetails? = null,
    val response: Response? = null,
    val ring: MessageId? = null,
    val boxKey: ByteArray? = null,
    val sealed: ByteArray? = null,
    val presence: Boolean = false,
    val media: MediaChunk? = null,
) {
    init {
        require(boxKey == null || boxKey.size == P256.PUBLIC_KEY_SIZE) { "box key must be ${P256.PUBLIC_KEY_SIZE} bytes" }
        require(sealed == null || text.isEmpty()) { "a sealed body carries its text inside" }
    }

    // ByteArray fields compare by reference in a generated equals.
    override fun equals(other: Any?): Boolean =
        other is MessageBody && text == other.text && location == other.location && senderName == other.senderName &&
            details == other.details && response == other.response && ring == other.ring &&
            boxKey.contentEquals(other.boxKey) && sealed.contentEquals(other.sealed) && presence == other.presence &&
            media == other.media

    override fun hashCode(): Int = text.hashCode() * 31 + (sealed?.contentHashCode() ?: 0)

    fun encode(): ByteArray {
        val text = text.encodeToByteArray()
        val name = senderName?.takeIf { it.isNotBlank() }?.let { utf8Prefix(it, MAX_NAME_BYTES) }
        var flags = 0
        var more = 0
        var size = 1 + text.size
        if (presence) more = more or MORE_PRESENCE
        if (media != null) {
            more = more or MORE_MEDIA
            size += MEDIA_HEADER + media.bytes.size
        }
        if (more != 0) {
            flags = flags or FLAG_MORE
            size += 1
        }
        if (location != null) {
            flags = flags or FLAG_LOCATION
            size += LOCATION_SIZE
        }
        if (name != null) {
            flags = flags or FLAG_NAME
            size += 1 + name.size
        }
        if (details != null) {
            flags = flags or FLAG_DETAILS
            size += DETAILS_SIZE
        }
        if (response != null) {
            flags = flags or FLAG_RESPONSE
            size += RESPONSE_SIZE
        }
        if (ring != null) {
            flags = flags or FLAG_RING
            size += RING_SIZE
        }
        if (boxKey != null) {
            flags = flags or FLAG_BOX_KEY
            size += P256.PUBLIC_KEY_SIZE
        }
        if (sealed != null) {
            flags = flags or FLAG_SEALED
            size += sealed.size
        }
        val buf = ByteBuffer.allocate(size)
        buf.put(flags.toByte())
        if (more != 0) buf.put(more.toByte())
        location?.let {
            buf.putInt(it.latE7)
            buf.putInt(it.lonE7)
            buf.putShort(it.accuracyMeters.toShort())
        }
        name?.let {
            buf.put(it.size.toByte())
            buf.put(it)
        }
        details?.let {
            buf.put(it.kind.code.toByte())
            buf.put(it.people.toByte())
            buf.put((if (it.injured) DETAIL_INJURED else 0).toByte())
        }
        response?.let {
            buf.putLong(it.sos.value)
            buf.put(it.status.code.toByte())
        }
        ring?.let { buf.putLong(it.value) }
        media?.let {
            buf.put(it.kind.code.toByte())
            buf.putLong(it.id)
            buf.put(it.index.toByte())
            buf.put(it.count.toByte())
            buf.putShort(it.bytes.size.toShort())
            buf.put(it.bytes)
        }
        boxKey?.let { buf.put(it) }
        sealed?.let { buf.put(it) }
        buf.put(text)
        return buf.array()
    }

    companion object {
        /** The PS limit for an SOS. Longer chat messages are allowed up to the packet payload cap. */
        const val SOS_MAX_CHARS = 280
        const val MAX_NAME_BYTES = 32

        private const val FLAG_LOCATION = 0x01
        private const val FLAG_NAME = 0x02
        private const val FLAG_DETAILS = 0x04
        private const val FLAG_RESPONSE = 0x08
        private const val FLAG_RING = 0x10
        private const val FLAG_BOX_KEY = 0x20
        private const val FLAG_SEALED = 0x40
        private const val FLAG_MORE = 0x80
        private const val MORE_PRESENCE = 0x01
        private const val MORE_MEDIA = 0x02
        private const val LOCATION_SIZE = 10
        private const val DETAILS_SIZE = 3
        private const val RESPONSE_SIZE = 9
        private const val RING_SIZE = 8
        private const val MEDIA_HEADER = 13
        private const val DETAIL_INJURED = 0x01

        fun decode(bytes: ByteArray): MessageBody {
            val buf = ByteBuffer.wrap(bytes)
            try {
                val flags = buf.get().toUByte().toInt()
                val more = if (flags and FLAG_MORE != 0) buf.get().toUByte().toInt() else 0
                val location = if (flags and FLAG_LOCATION != 0) {
                    Location(buf.getInt(), buf.getInt(), buf.getShort().toUShort().toInt())
                } else {
                    null
                }
                val name = if (flags and FLAG_NAME != 0) {
                    val length = buf.get().toUByte().toInt()
                    ByteArray(length).also { buf.get(it) }.decodeToString()
                } else {
                    null
                }
                val details = if (flags and FLAG_DETAILS != 0) {
                    val kind = SosKind.fromCode(buf.get().toUByte().toInt())
                    val people = buf.get().toUByte().toInt()
                    SosDetails(kind, people, injured = buf.get().toInt() and DETAIL_INJURED != 0)
                } else {
                    null
                }
                val response = if (flags and FLAG_RESPONSE != 0) {
                    val sos = MessageId(buf.getLong())
                    val code = buf.get().toUByte().toInt()
                    Response(sos, ResponseStatus.fromCode(code) ?: throw MalformedPacketException("response status $code"))
                } else {
                    null
                }
                val ring = if (flags and FLAG_RING != 0) MessageId(buf.getLong()) else null
                val media = if (more and MORE_MEDIA != 0) {
                    val code = buf.get().toUByte().toInt()
                    val kind = MediaKind.fromCode(code) ?: throw MalformedPacketException("media kind $code")
                    val id = buf.getLong()
                    val index = buf.get().toUByte().toInt()
                    val count = buf.get().toUByte().toInt()
                    val length = buf.getShort().toUShort().toInt()
                    if (count !in 1..MediaChunk.MAX_CHUNKS || index >= count || length > MediaChunk.MAX_BYTES) {
                        throw MalformedPacketException("media chunk $index of $count, $length bytes")
                    }
                    MediaChunk(id, kind, index, count, ByteArray(length).also { buf.get(it) })
                } else {
                    null
                }
                val boxKey = if (flags and FLAG_BOX_KEY != 0) ByteArray(P256.PUBLIC_KEY_SIZE).also { buf.get(it) } else null
                val rest = ByteArray(buf.remaining()).also { buf.get(it) }
                val presence = more and MORE_PRESENCE != 0
                return if (flags and FLAG_SEALED != 0) {
                    MessageBody("", location, name, details, response, ring, boxKey, sealed = rest, presence = presence, media = media)
                } else {
                    MessageBody(rest.decodeToString(), location, name, details, response, ring, boxKey, presence = presence, media = media)
                }
            } catch (e: BufferUnderflowException) {
                throw MalformedPacketException("truncated message body")
            }
        }
    }
}

/** UTF-8 bytes of [value], cut at a character boundary so a long name never ends in half a character. */
internal fun utf8Prefix(value: String, maxBytes: Int): ByteArray {
    var end = value.length
    var bytes = value.encodeToByteArray()
    while (bytes.size > maxBytes) {
        end--
        if (end > 0 && Character.isLowSurrogate(value[end])) end--
        bytes = value.substring(0, end).encodeToByteArray()
    }
    return bytes
}

/**
 * Payload of a [PacketType.ACK], the delivery receipt sent back to a message's
 * origin. [hops] is how many radio links the original took to reach the sender
 * of this receipt. [route] is the original's path reversed, ending at its
 * origin: relays send the receipt back along it instead of flooding the mesh.
 * [name] is the receiver's display name, so the sender sees who got it even
 * from nodes it has never heard from.
 *
 * ```
 * acked message id u64 | hops u8 | route length n u8 | n node ids i32 | [name length u8 | name UTF-8]
 * ```
 */
data class AckBody(
    val acked: MessageId,
    val hops: Int,
    val route: List<NodeId> = emptyList(),
    val name: String? = null,
) {
    init {
        require(route.size <= MAX_ROUTE) { "route longer than $MAX_ROUTE" }
    }

    fun encode(): ByteArray {
        val nameBytes = name?.takeIf { it.isNotBlank() }?.let { utf8Prefix(it, MessageBody.MAX_NAME_BYTES) }
        val buf = ByteBuffer.allocate(MIN_SIZE + 4 * route.size + (nameBytes?.let { 1 + it.size } ?: 0))
            .putLong(acked.value)
            .put(hops.toByte())
            .put(route.size.toByte())
        route.forEach { buf.putInt(it.value) }
        nameBytes?.let { buf.put(it.size.toByte()).put(it) }
        return buf.array()
    }

    companion object {
        const val MIN_SIZE = 10

        /** Every relay of a max-TTL path, plus the origin. */
        const val MAX_ROUTE = Packet.MAX_TTL + 1

        fun decode(bytes: ByteArray): AckBody {
            if (bytes.size < MIN_SIZE) throw MalformedPacketException("ack body is ${bytes.size} bytes")
            val buf = ByteBuffer.wrap(bytes)
            val acked = MessageId(buf.getLong())
            val hops = buf.get().toUByte().toInt()
            val n = buf.get().toUByte().toInt()
            if (n > MAX_ROUTE || bytes.size < MIN_SIZE + 4 * n) {
                throw MalformedPacketException("ack route of $n in ${bytes.size} bytes")
            }
            val route = List(n) { NodeId(buf.getInt()) }
            val name = if (buf.hasRemaining()) {
                val length = buf.get().toUByte().toInt()
                if (length != buf.remaining()) throw MalformedPacketException("ack name of $length in ${buf.remaining()} bytes")
                ByteArray(length).also { buf.get(it) }.decodeToString()
            } else {
                null
            }
            return AckBody(acked, hops, route, name)
        }
    }
}
