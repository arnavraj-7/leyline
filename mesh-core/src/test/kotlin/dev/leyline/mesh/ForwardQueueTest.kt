package dev.leyline.mesh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForwardQueueTest {
    private var nextId = 1L

    private fun packet(priority: Priority = Priority.NORMAL) = Packet(
        id = MessageId(nextId++),
        type = PacketType.MESSAGE,
        priority = priority,
        origin = NodeId(1),
        destination = NodeId.BROADCAST,
        ttl = 3,
        createdAt = 0,
        path = emptyList(),
        payload = byteArrayOf(),
    )

    @Test
    fun `sos jumps ahead of normal, fifo within a priority`() {
        val queue = ForwardQueue(10)
        val n1 = packet()
        val n2 = packet()
        val sos = packet(Priority.SOS)
        listOf(n1, n2, sos).forEach { queue.offer(it) }

        assertEquals(listOf(sos, n1, n2), List(3) { queue.poll() })
        assertNull(queue.poll())
    }

    @Test
    fun `same id is queued once`() {
        val queue = ForwardQueue(10)
        val p = packet()
        assertTrue(queue.offer(p))
        assertFalse(queue.offer(p))
        assertEquals(1, queue.size)
    }

    @Test
    fun `a better copy replaces a queued weaker one in place`() {
        val queue = ForwardQueue(10)
        val first = packet()
        val weak = packet().copy(ttl = 1)
        queue.offer(first)
        queue.offer(weak)

        assertTrue(queue.offer(weak.copy(ttl = 3)))
        assertFalse(queue.offer(weak.copy(ttl = 2)), "not better than what is queued now")
        assertEquals(first, queue.poll())
        assertEquals(3, queue.poll()!!.ttl)
    }

    @Test
    fun `full queue evicts oldest normal to make room for sos`() {
        val queue = ForwardQueue(2)
        val oldest = packet()
        val newer = packet()
        queue.offer(oldest)
        queue.offer(newer)
        val sos = packet(Priority.SOS)

        assertTrue(queue.offer(sos))
        assertFalse(oldest.id in queue)
        assertEquals(listOf(sos, newer), List(2) { queue.poll() })
    }

    @Test
    fun `full queue rejects normal and never evicts sos`() {
        val queue = ForwardQueue(2)
        queue.offer(packet(Priority.SOS))
        queue.offer(packet(Priority.SOS))

        assertFalse(queue.offer(packet()))
        assertFalse(queue.offer(packet(Priority.SOS)))
        assertEquals(2, queue.size)
    }

    @Test
    fun `remove takes a packet out wherever it is`() {
        val queue = ForwardQueue(10)
        val a = packet()
        val b = packet()
        queue.offer(a)
        queue.offer(b)

        assertTrue(queue.remove(a.id))
        assertEquals(b, queue.poll())
        assertTrue(queue.isEmpty())
    }
}
