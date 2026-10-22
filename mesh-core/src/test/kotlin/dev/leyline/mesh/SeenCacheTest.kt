package dev.leyline.mesh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SeenCacheTest {
    private var now = 0L
    private val cache = SeenCache(capacity = 3, expiryMillis = 1_000, clock = { now })

    @Test
    fun `second sighting is a duplicate`() {
        assertTrue(cache.markIfNew(MessageId(1)))
        assertFalse(cache.markIfNew(MessageId(1)))
    }

    @Test
    fun `a copy with more ttl left is better, an equal or worse one a duplicate`() {
        val id = MessageId(1)
        assertEquals(Seen.NEW, cache.offer(id, ttl = 1))
        assertEquals(Seen.BETTER, cache.offer(id, ttl = 3))
        assertEquals(Seen.DUPLICATE, cache.offer(id, ttl = 3))
        assertEquals(Seen.DUPLICATE, cache.offer(id, ttl = 2))
    }

    @Test
    fun `ids marked as handled are never better`() {
        cache.markIfNew(MessageId(1))
        assertEquals(Seen.DUPLICATE, cache.offer(MessageId(1), ttl = Packet.MAX_TTL))
    }

    @Test
    fun `capacity evicts the oldest id`() {
        (1L..4L).forEach { cache.markIfNew(MessageId(it)) }
        assertEquals(3, cache.size)
        assertTrue(cache.markIfNew(MessageId(1)), "id 1 was evicted, so it counts as new again")
    }

    @Test
    fun `entries expire after the window`() {
        cache.markIfNew(MessageId(1))
        now = 999
        assertFalse(cache.markIfNew(MessageId(1)))
        now = 1_000
        assertTrue(cache.markIfNew(MessageId(1)))
    }
}
