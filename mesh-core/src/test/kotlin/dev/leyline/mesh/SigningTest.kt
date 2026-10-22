package dev.leyline.mesh

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.interfaces.ECPublicKey
import kotlin.random.Random

class SigningTest {
    private val signer = SoftwareSigner()
    private val packet = Packet(
        id = MessageId(42),
        type = PacketType.MESSAGE,
        priority = Priority.SOS,
        origin = signer.nodeId,
        destination = NodeId.BROADCAST,
        ttl = 4,
        createdAt = 1_760_000_000_000L,
        path = emptyList(),
        payload = MessageBody("SOS: trapped near gate 4").encode(),
    )
    private val signed = PacketSignatures.sign(packet, signer)

    @Test
    fun `compressed keys decompress to the same point, for both y parities`() {
        val parities = HashSet<Byte>()
        repeat(20) {
            val key = P256.generate().public as ECPublicKey
            val compressed = P256.compress(key)
            parities += compressed[0]
            assertEquals(33, compressed.size)
            assertEquals(key.w, P256.decompress(compressed).w)
        }
        assertEquals(setOf<Byte>(2, 3), parities)
    }

    @Test
    fun `a key that is not on the curve is refused`() {
        // About half of all x values have no point on the curve; find one.
        val offCurve = generateSequence(1) { it + 1 }
            .map { byteArrayOf(2) + ByteArray(31) + byteArrayOf(it.toByte()) }
            .first { runCatching { P256.decompress(it) }.isFailure }
        assertThrows<IllegalArgumentException> { P256.decompress(offCurve) }
        assertFalse(P256.verify(offCurve, byteArrayOf(1), ByteArray(64)))
    }

    @Test
    fun `DER and raw signatures convert both ways, including short integers`() {
        repeat(50) {
            val raw = signer.sign(Random.nextBytes(16))
            assertEquals(64, raw.size)
            assertArrayEquals(raw, P256.derToRaw(P256.rawToDer(raw)))
        }
        // r = 1 and s with its top bit set: the shortest and the padded DER integer.
        val edge = ByteArray(31) + byteArrayOf(1) + ByteArray(32) { 0xFF.toByte() }
        assertArrayEquals(edge, P256.derToRaw(P256.rawToDer(edge)))
    }

    @Test
    fun `a signed packet verifies, and relays may change only ttl and path`() {
        assertEquals(Verdict.VALID, PacketSignatures.verify(signed))
        val relayed = signed.copy(ttl = 2, path = listOf(NodeId(7), NodeId(8)))
        assertEquals(Verdict.VALID, PacketSignatures.verify(relayed))
    }

    @Test
    fun `changing what the origin wrote breaks the signature`() {
        val tampered = listOf(
            signed.copy(payload = MessageBody("All clear, go home").encode()),
            signed.copy(priority = Priority.NORMAL),
            signed.copy(createdAt = signed.createdAt + 1),
            signed.copy(destination = NodeId(5)),
            signed.copy(id = MessageId(43)),
            signed.copy(type = PacketType.ACK),
        )
        tampered.forEach { assertEquals(Verdict.BAD_SIGNATURE, PacketSignatures.verify(it)) }
    }

    @Test
    fun `claiming another node's id fails even with a valid signature`() {
        val other = SoftwareSigner()
        // Mallory signs correctly with her own key but writes our id as the origin.
        val spoof = PacketSignatures.sign(packet.copy(origin = other.nodeId), signer)
        assertEquals(Verdict.WRONG_ORIGIN, PacketSignatures.verify(spoof))
        // Or swaps in her key without being able to re-sign as us.
        val swapped = signed.copy(auth = PacketAuth(other.publicKey, signed.auth!!.signature))
        assertEquals(Verdict.WRONG_ORIGIN, PacketSignatures.verify(swapped))
    }

    @Test
    fun `unsigned packets and random signatures are reported, never thrown`() {
        assertEquals(Verdict.UNSIGNED, PacketSignatures.verify(packet))
        val garbage = signed.copy(auth = PacketAuth(signer.publicKey, Random.nextBytes(64)))
        assertEquals(Verdict.BAD_SIGNATURE, PacketSignatures.verify(garbage))
    }

    @Test
    fun `signed packets round trip through the codec with 97 extra bytes`() {
        val bytes = PacketCodec.encode(signed)
        assertEquals(PacketCodec.encode(packet).size + 97, bytes.size)
        val decoded = PacketCodec.decode(bytes)
        assertEquals(signed, decoded)
        assertEquals(Verdict.VALID, PacketSignatures.verify(decoded))

        // One flipped bit in the payload on the air.
        val flipped = bytes.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        assertEquals(Verdict.BAD_SIGNATURE, PacketSignatures.verify(PacketCodec.decode(flipped)))
        assertThrows<MalformedPacketException> { PacketCodec.decode(bytes.copyOf(bytes.size - 1)) }
    }

    @Test
    fun `the first key seen for a node is pinned`() = runTest {
        val pins = InMemoryKeyPins()
        val node = NodeId(1)
        assertTrue(pins.check(node, signer.publicKey))
        assertTrue(pins.check(node, signer.publicKey.copyOf()))
        assertFalse(pins.check(node, SoftwareSigner().publicKey))
    }
}
