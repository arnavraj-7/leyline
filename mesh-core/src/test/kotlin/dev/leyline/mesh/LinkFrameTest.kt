package dev.leyline.mesh

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.random.Random

class LinkFrameTest {
    private var now = 0L
    private val bytes = Random(7).nextBytes(400)

    private fun frames(data: ByteArray = bytes, transferId: Int = 1, frameSize: Int = 180) =
        LinkFrameCodec.split(data, transferId, frameSize).map { LinkFrameCodec.decode(it) as LinkFrame.Data }

    @Test
    fun `hello round trips`() {
        val hello = LinkFrame.Hello(NodeId(0x1234ABCD))
        val encoded = LinkFrameCodec.encode(hello)
        assertEquals(6, encoded.size)
        assertEquals(hello, LinkFrameCodec.decode(encoded))

        val named = LinkFrame.Hello(NodeId(7), name = "Ayush")
        assertEquals(named, LinkFrameCodec.decode(LinkFrameCodec.encode(named)))
    }

    @Test
    fun `split respects frame size and reassembles`() {
        val encoded = LinkFrameCodec.split(bytes, transferId = 9, frameSize = 180)
        assertEquals(3, encoded.size) // 175 + 175 + 50
        encoded.forEach { assert(it.size <= 180) }

        val reassembler = Reassembler(clock = { now })
        val results = encoded.map { reassembler.accept(LinkFrameCodec.decode(it) as LinkFrame.Data) }
        assertNull(results[0])
        assertNull(results[1])
        assertArrayEquals(bytes, results[2])
    }

    @Test
    fun `out of order and repeated frames still reassemble once`() {
        val (f0, f1, f2) = frames()
        val reassembler = Reassembler(clock = { now })

        assertNull(reassembler.accept(f2))
        assertNull(reassembler.accept(f0))
        assertNull(reassembler.accept(f0))
        assertArrayEquals(bytes, reassembler.accept(f1))
        assertEquals(0, reassembler.pendingTransfers)
    }

    @Test
    fun `the smallest ble frame works`() {
        val encoded = LinkFrameCodec.split(bytes, 1, LinkFrameCodec.MIN_FRAME_SIZE)
        val reassembler = Reassembler(clock = { now })
        val out = encoded.map { reassembler.accept(LinkFrameCodec.decode(it) as LinkFrame.Data) }.last()
        assertArrayEquals(bytes, out)
    }

    @Test
    fun `interleaved transfers do not mix`() {
        val other = Random(8).nextBytes(300)
        val a = frames(bytes, transferId = 1)
        val b = frames(other, transferId = 2)
        val reassembler = Reassembler(clock = { now })

        val results = listOf(a[0], b[0], a[1], b[1], a[2]).map { reassembler.accept(it) }
        assertArrayEquals(other, results[3])
        assertArrayEquals(bytes, results[4])
    }

    @Test
    fun `partial transfer is forgotten after the timeout`() {
        val (f0, f1, f2) = frames()
        val reassembler = Reassembler(clock = { now }, timeoutMillis = 1_000)
        reassembler.accept(f0)
        reassembler.accept(f1)

        now = 1_000
        assertNull(reassembler.accept(f2), "the first two frames expired, so this starts a new transfer")
        assertEquals(1, reassembler.pendingTransfers)
    }

    @Test
    fun `oldest partial is evicted beyond maxPending`() {
        val reassembler = Reassembler(clock = { now }, maxPending = 2)
        (1..3).forEach { reassembler.accept(frames(transferId = it)[0]) }
        assertEquals(2, reassembler.pendingTransfers)
    }

    @Test
    fun `transfer larger than maxBytes is dropped`() {
        val reassembler = Reassembler(clock = { now }, maxBytes = 300)
        val (f0, f1) = frames()
        assertNull(reassembler.accept(f0))
        assertNull(reassembler.accept(f1), "350 bytes buffered > 300, transfer abandoned")
        assertEquals(0, reassembler.pendingTransfers)
    }

    @Test
    fun `malformed frames are rejected`() {
        assertThrows<MalformedPacketException> { LinkFrameCodec.decode(byteArrayOf()) }
        assertThrows<MalformedPacketException> { LinkFrameCodec.decode(byteArrayOf(9)) }
        assertThrows<MalformedPacketException> { LinkFrameCodec.decode(byteArrayOf(1, 1, 0)) }
        // index 2 of 2
        assertThrows<MalformedPacketException> { LinkFrameCodec.decode(byteArrayOf(2, 0, 1, 2, 2, 42)) }
        // no chunk
        assertThrows<MalformedPacketException> { LinkFrameCodec.decode(byteArrayOf(2, 0, 1, 0, 1)) }
    }
}
