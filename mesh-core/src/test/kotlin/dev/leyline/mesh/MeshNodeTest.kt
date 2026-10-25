@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.leyline.mesh

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeshNodeTest {
    private val sos = MessageBody("SOS: trapped near library").encode()

    @Test
    fun `sos crosses a relay and receipts come back with hop counts`() = runTest {
        val net = FakeNetwork(this)
        val (a, b, c) = (1..3).map { net.add(it) }
        net.link(1, 2)
        net.link(2, 3)
        net.settle()

        val sent = a.node.send(sos, priority = Priority.SOS)
        net.settle()

        val atC = c.delivered.single()
        assertEquals(sent.id, atC.packet.id)
        assertEquals(2, atC.hops)
        assertEquals(listOf(NodeId(2)), atC.packet.path)
        assertEquals(1, b.delivered.single().hops)

        val receipts = a.receipts.associate { it.by to it.hops }
        assertEquals(mapOf(NodeId(2) to 1, NodeId(3) to 2), receipts)
        assertEquals("node 3", a.receipts.single { it.by == NodeId(3) }.name)
    }

    @Test
    fun `store and forward carries a message across a gap`() = runTest {
        // A and C are never in range of each other or at the same time as B.
        val net = FakeNetwork(this)
        val (a, b, c) = (1..3).map { net.add(it) }
        a.node.send(sos, priority = Priority.SOS)
        net.settle()
        assertTrue(b.delivered.isEmpty())

        net.link(1, 2) // B walks past A
        net.settle()
        assertEquals(1, b.delivered.size)
        net.unlink(1, 2)

        net.link(2, 3) // later, B walks past C
        net.settle()
        assertEquals(2, c.delivered.single().hops)
    }

    @Test
    fun `forward events show how long a carried message waited`() = runTest {
        val net = FakeNetwork(this)
        val (a, b) = (1..2).map { net.add(it) }
        net.add(3)
        net.link(1, 2)
        net.settle()
        val sent = a.node.send(sos, priority = Priority.SOS)
        net.settle()
        net.unlink(1, 2)

        testScheduler.advanceTimeBy(60_000) // B carries it for a minute
        net.link(2, 3)
        net.settle()

        val direct = a.events.filterIsInstance<MeshEvent.Forwarded>().single { it.packet.id == sent.id }
        assertEquals(NodeId(2), direct.to)
        assertTrue(direct.heldMillis < 1_000, "relayed straight away: ${direct.heldMillis} ms")
        val carried = b.events.filterIsInstance<MeshEvent.Forwarded>().single { it.packet.id == sent.id }
        assertEquals(NodeId(3), carried.to)
        assertTrue(carried.heldMillis >= 60_000, "carried: ${carried.heldMillis} ms")
        assertEquals(listOf(NodeId(2)), carried.packet.path)
    }

    @Test
    fun `receipts report the route they came back on`() = runTest {
        val net = FakeNetwork(this)
        val (a) = (1..3).map { net.add(it) }
        net.link(1, 2)
        net.link(2, 3)
        net.settle()

        a.node.send(sos, priority = Priority.SOS)
        net.settle()

        assertEquals(listOf(NodeId(3), NodeId(2), NodeId(1)), a.receipts.single { it.by == NodeId(3) }.route)
    }

    @Test
    fun `a copy we already have is reported as a drop with its reason`() = runTest {
        val net = FakeNetwork(this)
        val b = net.add(2)
        val packet = Packet(
            id = MessageId(5),
            type = PacketType.MESSAGE,
            priority = Priority.NORMAL,
            origin = NodeId(1),
            destination = NodeId.BROADCAST,
            ttl = 3,
            createdAt = 0,
            path = emptyList(),
            payload = MessageBody("hello").encode(),
        )
        net.inject(2, 1, PacketCodec.encode(packet))
        net.inject(2, 3, PacketCodec.encode(packet.copy(ttl = 2, path = listOf(NodeId(3)))))
        net.inject(2, 4, PacketCodec.encode(packet.copy(origin = NodeId(2))))
        net.settle()

        val drops = b.events.filterIsInstance<MeshEvent.Dropped>().associate { it.neighbour to it.reason }
        assertEquals(mapOf(NodeId(3) to DropReason.DUPLICATE, NodeId(4) to DropReason.OWN_PACKET), drops)
    }

    @Test
    fun `full mesh delivers once per node and never sends a packet back`() = runTest {
        val net = FakeNetwork(this)
        val nodes = (1..5).map { net.add(it) }
        for (i in 1..5) for (j in i + 1..5) net.link(i, j)
        net.settle()

        nodes[0].node.send(MessageBody("hello all").encode())
        net.settle()

        nodes.drop(1).forEach { assertEquals(1, it.delivered.size) }
        // Naive flooding writes n*(n-1) = 20 packets here. Holder tracking skips
        // every neighbour already on the path or heard from.
        val total = net.writes.values.sum()
        println("full mesh of 5: $total writes (naive flooding: 20)")
        assertTrue(total < 20, "wrote $total packets")
    }

    @Test
    fun `unicast to a direct neighbour is not flooded to the others`() = runTest {
        val net = FakeNetwork(this)
        val (a, b, c) = (1..3).map { net.add(it) }
        net.link(1, 2)
        net.link(1, 3)
        net.settle()

        a.node.send(MessageBody("just for B").encode(), destination = NodeId(2))
        net.settle()

        assertEquals(1, b.delivered.size)
        assertEquals(0, c.node.stats.value.relayed + c.node.stats.value.delivered)
        assertEquals(1, a.receipts.size)
    }

    @Test
    fun `receipt clears a delivered unicast from every outbox`() = runTest {
        val net = FakeNetwork(this)
        val (a, b, c) = (1..3).map { net.add(it) }
        net.link(1, 2)
        net.link(2, 3)
        net.settle()

        val sent = a.node.send(MessageBody("to C").encode(), destination = NodeId(3))
        net.settle()

        assertEquals(1, c.delivered.size)
        assertEquals(1, a.receipts.size)
        assertNull(a.outbox.get(sent.id))
        assertNull(b.outbox.get(sent.id))
    }

    @Test
    fun `topology mode forces a hop between nodes in range`() = runTest {
        val net = FakeNetwork(this)
        val (a, _, c) = (1..3).map { net.add(it) }
        a.node.setBlocked(setOf(NodeId(3)))
        c.node.setBlocked(setOf(NodeId(1)))
        net.link(1, 2)
        net.link(2, 3)
        net.link(1, 3)
        net.settle()

        a.node.send(sos, priority = Priority.SOS)
        net.settle()

        assertEquals(2, c.delivered.single().hops)
    }

    @Test
    fun `relay off still delivers but does not forward`() = runTest {
        val net = FakeNetwork(this)
        val (a, b, c) = (1..3).map { net.add(it) }
        b.node.relayEnabled = false
        net.link(1, 2)
        net.link(2, 3)
        net.settle()

        a.node.send(sos, priority = Priority.SOS)
        net.settle()

        assertEquals(1, b.delivered.size)
        assertTrue(c.delivered.isEmpty())
    }

    @Test
    fun `garbage from a neighbour is counted, not crashed on`() = runTest {
        val net = FakeNetwork(this)
        val a = net.add(1)
        net.add(2)
        net.link(1, 2)
        net.inject(to = 1, from = 2, bytes = byteArrayOf(1, 2, 3))
        net.settle()
        assertEquals(1, a.node.stats.value.malformed)
    }

    @Test
    fun `a failed write is retried at once`() = runTest {
        val net = FakeNetwork(this)
        val (a, b) = (1..2).map { net.add(it) }
        net.link(1, 2)
        net.settle()
        net.failWrites = 1 // e.g. GATT error 133 with the link still up
        a.node.send(sos, priority = Priority.SOS)
        net.settle()
        assertNotNull(b.delivered.singleOrNull())
        assertEquals(1, a.node.stats.value.sendFailures)
    }

    @Test
    fun `writes that keep failing are retried by the periodic resync`() = runTest {
        val net = FakeNetwork(this)
        val (a, b) = (1..2).map { net.add(it) }
        net.link(1, 2)
        net.settle()
        net.failWrites = 3 // the first try and both quick retries
        a.node.send(sos, priority = Priority.SOS)
        net.settle()
        assertTrue(b.delivered.isEmpty())
        assertEquals(3, a.node.stats.value.sendFailures)

        testScheduler.advanceTimeBy(15_000)
        testScheduler.runCurrent()
        assertNotNull(b.delivered.singleOrNull())
    }

    @Test
    fun `receipts go back along the reverse path instead of flooding`() = runTest {
        // Ring 1-2-3-4-5-6-1. 1 sends to 3: 2 hops one way, 4 the other; whichever copy wins the race.
        val net = FakeNetwork(this)
        (1..6).map { net.add(it) }
        (1..6).forEach { net.link(it, it % 6 + 1) }
        net.settle()
        net.nodes.getValue(NodeId(1)).node.send(MessageBody("to 3").encode(), destination = NodeId(3))
        net.settle()
        val before = net.writes.values.sum()
        assertEquals(1, net.nodes.getValue(NodeId(1)).receipts.size)

        // Only the receipt is left in flight now; no new writes may appear later.
        testScheduler.advanceTimeBy(60_000)
        testScheduler.runCurrent()
        assertEquals(before, net.writes.values.sum())
        val receiptWrites = net.nodes.values.sumOf { node ->
            node.events.filterIsInstance<MeshEvent.Heard>().count { it.packet.type == PacketType.ACK }
        }
        val hops = net.nodes.getValue(NodeId(3)).delivered.single().hops
        assertEquals(hops, receiptWrites, "one write per hop of the route the message took")
    }

    @Test
    fun `ids known before a restart are not delivered again`() = runTest {
        val net = FakeNetwork(this)
        val a = net.add(1)
        val packet = a.node.send(sos, priority = Priority.SOS)
        val b = net.add(2, knownIds = listOf(packet.id))
        net.link(1, 2)
        net.settle()
        assertTrue(b.delivered.isEmpty())
        assertEquals(1, b.node.stats.value.duplicates)
    }

    @Test
    fun `the transport hears how much is still waiting before each send`() = runTest {
        val net = FakeNetwork(this)
        val (a) = (1..2).map { net.add(it) }
        val sent = List(20) { a.node.send(ByteArray(1_000)) }
        net.link(1, 2) // B arrives to a backlog of 20 carried messages
        net.settle()

        val hints = net.backlogs.filter { it.first == NodeId(1) }.map { it.second }
        val each = PacketCodec.encodedSize(sent.first())
        assertEquals(20, hints.size)
        assertEquals(20 * each, hints.first())
        assertEquals(each, hints.last())
    }
}
