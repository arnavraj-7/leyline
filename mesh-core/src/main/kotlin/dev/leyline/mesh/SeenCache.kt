package dev.leyline.mesh

/** What [SeenCache.offer] found. */
enum class Seen {
    NEW,

    /** Seen before, but this copy has more hops left than any earlier one. */
    BETTER,
    DUPLICATE,
}

/**
 * Remembers recently handled message ids so each node processes a message once,
 * plus the most hops-left (TTL) any copy of it arrived with.
 *
 * The TTL matters because flooding races: the first copy to arrive may have come
 * the long way with little TTL left, while a later copy that came the short way
 * can still reach further. Without it, nodes within range would be missed.
 *
 * Bounded two ways so a long-running relay can't grow it forever: entries older
 * than [expiryMillis] are dropped, and at most [capacity] entries are kept as a
 * memory cap (oldest evicted first). Not thread-safe; the owner guards it.
 */
class SeenCache(
    private val capacity: Int,
    private val expiryMillis: Long,
    private val clock: () -> Long,
) {
    private class Entry(val seenAt: Long, var bestTtl: Int)

    private val entries = object : LinkedHashMap<MessageId, Entry>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MessageId, Entry>?): Boolean =
            size > capacity
    }

    val size: Int get() = entries.size

    /** Records a copy of [id] that arrived with [ttl] hops left. */
    fun offer(id: MessageId, ttl: Int): Seen {
        val now = clock()
        evictExpired(now)
        val entry = entries[id]
        return when {
            entry == null -> {
                entries[id] = Entry(now, ttl)
                Seen.NEW
            }
            ttl > entry.bestTtl -> {
                entry.bestTtl = ttl
                Seen.BETTER
            }
            else -> Seen.DUPLICATE
        }
    }

    /** Records [id] as fully handled (no copy will ever be better) and returns true if it was new. */
    fun markIfNew(id: MessageId): Boolean = offer(id, Int.MAX_VALUE) == Seen.NEW

    // Insertion order equals time order, so expired entries are all at the front.
    private fun evictExpired(now: Long) {
        val values = entries.values.iterator()
        while (values.hasNext() && now - values.next().seenAt >= expiryMillis) {
            values.remove()
        }
    }
}
