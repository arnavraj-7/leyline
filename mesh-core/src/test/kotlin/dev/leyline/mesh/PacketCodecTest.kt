package dev.leyline.mesh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PacketCodecTest {
    private val sample = Packet(
        id = MessageId(0x0123456789ABCDEFL),
        type = PacketType.MESSAGE,
        priority = Priority.SOS,
        origin = NodeId(0xA),
        destination = NodeId.BROADCAST,
        ttl = 3,
        createdAt = 1_760_000_000_000L,
        path = listOf(NodeId(0xB), NodeId(0xC)),
        payload = "Trapped near gate 4".encodeToByteArray(),
    )

    @Test
    fun `round trip keeps every field`() {
        assertEquals(sample, PacketCodec.decode(PacketCodec.encode(sample)))
    }

    @Test
    fun `encoded size matches the spec`() {
        // 31 fixed bytes + 2 relays * 4 + 19 payload bytes
        assertEquals(58, PacketCodec.encode(sample).size)
    }

    @Test
    fun `a maximum length 280 character SOS encodes to 311 bytes`() {
        val sos = sample.copy(payload = "x".repeat(280).encodeToByteArray(), path = emptyList())
        assertEquals(311, PacketCodec.encode(sos).size)
    }

    @Test
    fun `truncated bytes are rejected, not crashed on`() {
        val bytes = PacketCodec.encode(sample)
        assertThrows<MalformedPacketException> { PacketCodec.decode(bytes.copyOf(bytes.size - 1)) }
        assertThrows<MalformedPacketException> { PacketCodec.decode(ByteArray(0)) }
    }

    @Test
    fun `trailing garbage is rejected`() {
        val bytes = PacketCodec.encode(sample) + byteArrayOf(0)
        assertThrows<MalformedPacketException> { PacketCodec.decode(bytes) }
    }

    @Test
    fun `unknown version and type are rejected`() {
        val bytes = PacketCodec.encode(sample)
        assertThrows<MalformedPacketException> { PacketCodec.decode(bytes.copyOf().also { it[0] = 9 }) }
        assertThrows<MalformedPacketException> { PacketCodec.decode(bytes.copyOf().also { it[1] = 9 }) }
    }

    @Test
    fun `out of range ttl from the wire is rejected`() {
        val bytes = PacketCodec.encode(sample).also { it[3] = 99 }
        assertThrows<MalformedPacketException> { PacketCodec.decode(bytes) }
    }
}
