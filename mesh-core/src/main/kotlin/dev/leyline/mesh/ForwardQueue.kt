package dev.leyline.mesh

/**
 * Packets waiting to be written to one neighbour.
 *
 * SOS always leaves before NORMAL; within a priority it is first in, first out.
 * Bounded: when full, a new packet may only push out a packet of strictly lower
 * priority, so a chat burst can never delay or drop an SOS. Anything dropped here
 * is still in the [Outbox] and is replayed when the neighbour reconnects.
 *
 * Not thread-safe; [MeshNode] guards it with its lock.
 */
class ForwardQueue(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val lanes = Priority.entries.associateWith { ArrayDeque<Packet>() }
    private val queued = HashSet<MessageId>()

    val size: Int get() = queued.size

    fun isEmpty(): Boolean = queued.isEmpty()

    operator fun contains(id: MessageId): Boolean = id in queued

    /**
     * Returns false if there was no room, or the packet was already queued. A
     * queued copy with less TTL left is swapped in place for this better one.
     */
    fun offer(packet: Packet): Boolean {
        if (packet.id in queued) {
            val lane = lanes.getValue(packet.priority)
            val index = lane.indexOfFirst { it.id == packet.id }
            if (index < 0 || lane[index].ttl >= packet.ttl) return false
            lane[index] = packet
            return true
        }
        if (queued.size >= capacity && !evictBelow(packet.priority)) return false
        lanes.getValue(packet.priority).addLast(packet)
        queued += packet.id
        return true
    }

    fun poll(): Packet? {
        for (priority in DRAIN_ORDER) {
            val packet = lanes.getValue(priority).removeFirstOrNull() ?: continue
            queued -= packet.id
            return packet
        }
        return null
    }

    fun remove(id: MessageId): Boolean {
        if (id !in queued) return false
        lanes.values.forEach { lane -> lane.removeAll { it.id == id } }
        queued -= id
        return true
    }

    fun clear() {
        lanes.values.forEach { it.clear() }
        queued.clear()
    }

    // Drops the oldest packet of the lowest priority below [priority], if there is one.
    private fun evictBelow(priority: Priority): Boolean {
        for (lower in Priority.entries) {
            if (lower >= priority) return false
            val victim = lanes.getValue(lower).removeFirstOrNull() ?: continue
            queued -= victim.id
            return true
        }
        return false
    }

    private companion object {
        val DRAIN_ORDER = Priority.entries.reversed()
    }
}
