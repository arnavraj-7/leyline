package dev.leyline.mesh

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A packet this node is carrying for store-and-forward.
 *
 * [holders] are the nodes known to already have it: the origin, every relay in
 * its path, neighbours we sent it to, and neighbours we heard it from. A packet
 * is only ever sent to a neighbour outside this set.
 *
 * [expiresAt] is in this node's own clock. Phones in a blackout can't sync
 * time, so expiry never compares against another node's timestamp.
 */
data class StoredPacket(
    val packet: Packet,
    val storedAt: Long,
    val expiresAt: Long,
    val holders: Set<NodeId>,
)

/**
 * Durable queue of packets to keep offering to new neighbours. Written before a
 * packet is sent (write-ahead), so a crash or a dropped link loses nothing.
 * Operations are idempotent by [MessageId]. Implementations must be thread-safe.
 */
interface Outbox {
    /** Inserts [packet] unless its id is already stored; then only [holders] are merged. */
    suspend fun store(packet: Packet, holders: Set<NodeId>, storedAt: Long, expiresAt: Long)

    /**
     * Replaces the stored copy with [packet], which has more hops left, keeping
     * the original timestamps; inserts it if absent. Holders restart from
     * [holders]: neighbours given the weaker copy may need this one to reach further.
     */
    suspend fun upgrade(packet: Packet, holders: Set<NodeId>, storedAt: Long, expiresAt: Long)

    suspend fun addHolders(id: MessageId, holders: Set<NodeId>)

    suspend fun get(id: MessageId): StoredPacket?

    /** Unexpired packets, SOS first, then oldest first. */
    suspend fun live(now: Long): List<StoredPacket>

    suspend fun remove(id: MessageId)

    /** Deletes expired packets and returns how many were removed. */
    suspend fun purgeExpired(now: Long): Int
}

/**
 * Outbox kept in memory. Used by tests and the simulator; the app stores the
 * same data in Room. When over [capacity], the oldest NORMAL packet goes first.
 */
class InMemoryOutbox(private val capacity: Int = 1000) : Outbox {
    private val mutex = Mutex()
    private val packets = LinkedHashMap<MessageId, StoredPacket>()

    override suspend fun store(packet: Packet, holders: Set<NodeId>, storedAt: Long, expiresAt: Long) =
        mutex.withLock {
            val existing = packets[packet.id]
            if (existing != null) {
                packets[packet.id] = existing.copy(holders = existing.holders + holders)
                return@withLock
            }
            packets[packet.id] = StoredPacket(packet, storedAt, expiresAt, holders)
            while (packets.size > capacity) {
                val victim = packets.values.firstOrNull { it.packet.priority == Priority.NORMAL }
                    ?: packets.values.first()
                packets.remove(victim.packet.id)
            }
        }

    override suspend fun upgrade(packet: Packet, holders: Set<NodeId>, storedAt: Long, expiresAt: Long) {
        val replaced = mutex.withLock {
            val existing = packets[packet.id] ?: return@withLock false
            packets[packet.id] = existing.copy(packet = packet, holders = holders)
            true
        }
        if (!replaced) store(packet, holders, storedAt, expiresAt)
    }

    override suspend fun addHolders(id: MessageId, holders: Set<NodeId>) = mutex.withLock {
        packets[id]?.let { packets[id] = it.copy(holders = it.holders + holders) }
        Unit
    }

    override suspend fun get(id: MessageId): StoredPacket? = mutex.withLock { packets[id] }

    override suspend fun live(now: Long): List<StoredPacket> = mutex.withLock {
        packets.values
            .filter { it.expiresAt > now }
            .sortedWith(compareByDescending<StoredPacket> { it.packet.priority }.thenBy { it.storedAt })
    }

    override suspend fun remove(id: MessageId) = mutex.withLock {
        packets.remove(id)
        Unit
    }

    override suspend fun purgeExpired(now: Long): Int = mutex.withLock {
        val before = packets.size
        packets.values.removeAll { it.expiresAt <= now }
        before - packets.size
    }
}
