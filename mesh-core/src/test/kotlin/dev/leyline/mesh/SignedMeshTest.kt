@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.leyline.mesh

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The mesh with signatures required, as the app runs it. */
class SignedMeshTest {
    private val sos = MessageBody("SOS: trapped near library").encode()

    private fun forgedSos(claimedOrigin: NodeId, by: SoftwareSigner, id: Long = 99, ttl: Int = 4): Packet {
        val packet = Packet(
            id = MessageId(id),
            type = PacketType.MESSAGE,
            priority = Priority.SOS,
            origin = claimedOrigin,
            destination = NodeId.BROADCAST,
            ttl = ttl,
            createdAt = 0,
            path = emptyList(),
            payload = MessageBody("SOS: fake emergency at the north gate").encode(),
        )
        return PacketSignatures.sign(packet, by)
    }

    private fun FakeNetwork.TestNode.rejections() = events.filterIsInstance<MeshEvent.Rejected>()

    @Test
    fun `signed sos crosses relays and signed receipts come back`() = runTest {
        val net = FakeNetwork(this)
        val (a, b, c) = List(3) { net.addSigned() }
        net.link(a.id, b.id)
        net.link(b.id, c.id)
        net.settle()

        val sent = a.node.send(sos, priority = Priority.SOS)
        net.settle()

        assertEquals(sent.id, c.delivered.single().packet.id)
        assertEquals(Verdict.VALID, PacketSignatures.verify(c.delivered.single().packet))
        assertEquals(mapOf(b.id to 1, c.id to 2), a.receipts.associate { it.by to it.hops })
        listOf(a, b, c).forEach { assertEquals(0, it.node.stats.value.rejected) }
    }

    @Test
    fun `a forged sos is rejected at the first hop and goes no further`() = runTest {
        val net = FakeNetwork(this)
        val (a, b, c) = List(3) { net.addSigned() }
        net.link(a.id, b.id)
        net.link(b.id, c.id)
        net.settle()

        // Mallory, linked to B, sends an SOS that claims to come from A.
        val mallory = SoftwareSigner()
        net.inject(b.id, mallory.nodeId, PacketCodec.encode(forgedSos(a.id, mallory)))
        net.settle()

        assertTrue(b.delivered.isEmpty())
        assertTrue(c.delivered.isEmpty())
        assertEquals(Rejection.WRONG_ORIGIN, b.rejections().single().reason)
        assertEquals(mallory.nodeId, b.rejections().single().neighbour)
        assertTrue(c.rejections().isEmpty(), "B must not relay what it rejected")
    }

    @Test
    fun `a relay that edits a message gets it rejected downstream`() = runTest {
        val net = FakeNetwork(this)
        val a = net.addSigned()
        val c = net.addSigned()
        // A is linked to nobody, so its genuine signed packet just sits in its outbox.
        val sent = a.node.send(sos, priority = Priority.SOS)
        val stored = a.outbox.get(sent.id)!!.packet

        // A dishonest relay B forwards it with the text changed.
        val edited = stored.copy(ttl = 3, path = listOf(NodeId(0xB)), payload = MessageBody("All clear").encode())
        net.inject(c.id, NodeId(0xB), PacketCodec.encode(edited))
        net.settle()

        assertTrue(c.delivered.isEmpty())
        assertEquals(Rejection.BAD_SIGNATURE, c.rejections().single().reason)
    }

    @Test
    fun `unsigned packets are dropped when signatures are required`() = runTest {
        val net = FakeNetwork(this)
        val b = net.addSigned()
        val unsigned = forgedSos(NodeId(7), SoftwareSigner()).copy(auth = null)
        net.inject(b.id, NodeId(7), PacketCodec.encode(unsigned))
        net.settle()

        assertTrue(b.delivered.isEmpty())
        assertEquals(Rejection.UNSIGNED, b.rejections().single().reason)
        assertEquals(1, b.node.stats.value.rejected)
    }

    @Test
    fun `reusing a message id cannot replace the copy a relay holds`() = runTest {
        val net = FakeNetwork(this)
        val a = net.addSigned()
        val b = net.addSigned()
        net.link(a.id, b.id)
        net.settle()

        val sent = a.node.send(sos, priority = Priority.SOS, ttl = 2)
        net.settle()
        val held = b.outbox.get(sent.id)!!.packet

        // Mallory signs her own message correctly, but under A's id and with more TTL,
        // which would normally count as a better copy worth forwarding again.
        val mallory = SoftwareSigner()
        val squat = forgedSos(mallory.nodeId, mallory, id = sent.id.value, ttl = 4)
        assertEquals(Verdict.VALID, PacketSignatures.verify(squat))
        net.inject(b.id, mallory.nodeId, PacketCodec.encode(squat))
        net.settle()

        assertArrayEquals(held.payload, b.outbox.get(sent.id)!!.packet.payload)
        assertEquals(0, b.node.stats.value.upgrades)
    }
}
