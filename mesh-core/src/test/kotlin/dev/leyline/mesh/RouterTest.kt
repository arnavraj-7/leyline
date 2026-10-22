package dev.leyline.mesh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class RouterTest {
    private val a = NodeId(0xA)
    private val b = NodeId(0xB)
    private val c = NodeId(0xC)

    private fun router(self: NodeId, seed: Int = self.value) =
        Router(self, SeenCache(1024, 60_000, clock = { 0L }), clock = { 0L }, random = Random(seed))

    private fun accept(decision: RouteDecision) = decision as RouteDecision.Accept

    @Test
    fun `broadcast is delivered and forwarded with ttl down and path extended`() {
        val packet = router(a).originate("help".encodeToByteArray(), ttl = 3)
        val decision = accept(router(b).onReceive(packet))

        assertTrue(decision.deliver)
        val forwarded = decision.forward!!
        assertEquals(2, forwarded.ttl)
        assertEquals(listOf(b), forwarded.path)
        assertEquals(packet.id, forwarded.id)
    }

    @Test
    fun `A reaches C through B`() {
        val packet = router(a).originate("help".encodeToByteArray(), destination = c, ttl = 3)

        val atB = accept(router(b).onReceive(packet))
        assertFalse(atB.deliver, "B is a relay, not the destination")

        val atC = accept(router(c).onReceive(atB.forward!!))
        assertTrue(atC.deliver)
        assertNull(atC.forward, "unicast stops at its destination")
        assertEquals(1, atB.forward.relayCount)
    }

    @Test
    fun `same message twice is dropped as duplicate`() {
        val packet = router(a).originate(byteArrayOf(1))
        val relay = router(b)
        relay.onReceive(packet)
        assertEquals(RouteDecision.Drop(DropReason.DUPLICATE), relay.onReceive(packet))
    }

    @Test
    fun `a repeat with more ttl is forwarded again but not delivered again`() {
        val packet = router(a).originate(byteArrayOf(1), ttl = 3)
        val relay = router(c)
        val longWay = packet.copy(ttl = 1, path = listOf(NodeId(0xE), NodeId(0xF)))
        assertNull(accept(relay.onReceive(longWay)).forward, "ttl 1: delivered, not forwarded")

        val shortWay = accept(relay.onReceive(packet))
        assertTrue(shortWay.upgrade)
        assertFalse(shortWay.deliver)
        assertEquals(2, shortWay.forward!!.ttl)
        assertEquals(RouteDecision.Drop(DropReason.DUPLICATE), relay.onReceive(packet))
    }

    @Test
    fun `a better repeat of a message for us is just a duplicate`() {
        val packet = router(a).originate(byteArrayOf(1), destination = c, ttl = 3)
        val target = router(c)
        target.onReceive(packet.copy(ttl = 1, path = listOf(b)))
        assertEquals(RouteDecision.Drop(DropReason.DUPLICATE), target.onReceive(packet))
    }

    @Test
    fun `own message echoed back is dropped`() {
        val origin = router(a)
        val packet = origin.originate(byteArrayOf(1))
        val echo = accept(router(b).onReceive(packet)).forward!!
        assertEquals(RouteDecision.Drop(DropReason.OWN_PACKET), origin.onReceive(echo))
    }

    @Test
    fun `packet whose path already contains us is a loop`() {
        val packet = router(a).originate(byteArrayOf(1)).copy(path = listOf(c, b))
        assertEquals(RouteDecision.Drop(DropReason.LOOP), router(b).onReceive(packet))
    }

    @Test
    fun `ttl 1 is delivered but not forwarded`() {
        val packet = router(a).originate(byteArrayOf(1), ttl = 1)
        val decision = accept(router(b).onReceive(packet))
        assertTrue(decision.deliver)
        assertNull(decision.forward)
    }

    @Test
    fun `ttl bounds how far a message travels`() {
        // Line of 6 nodes: a message with ttl 3 reaches exactly 3 hops from the origin.
        val nodes = (1..6).map { router(NodeId(it)) }
        var packet: Packet? = nodes[0].originate(byteArrayOf(1), ttl = 3)
        var reached = 0
        for (node in nodes.drop(1)) {
            val current = packet ?: break
            val decision = accept(node.onReceive(current))
            if (decision.deliver) reached++
            packet = decision.forward
        }
        assertEquals(3, reached)
    }
}
