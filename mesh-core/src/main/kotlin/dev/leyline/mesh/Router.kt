package dev.leyline.mesh

import kotlin.random.Random

enum class DropReason { DUPLICATE, OWN_PACKET, LOOP }

sealed interface RouteDecision {
    data class Drop(val reason: DropReason) : RouteDecision

    /**
     * [deliver]: show it on this node. [forward]: the copy to send on to every
     * neighbour except the one it arrived from, or null to stop here.
     * [upgrade]: a repeat that carries more TTL than the copy we already handled;
     * it is never delivered again, only forwarded further.
     */
    data class Accept(val deliver: Boolean, val forward: Packet?, val upgrade: Boolean = false) : RouteDecision
}

/**
 * Flood routing with dedup and TTL. Pure decisions only: no I/O, no Android,
 * so every rule is unit-tested and the simulator runs the exact same code.
 */
class Router(
    private val self: NodeId,
    private val seen: SeenCache,
    private val clock: () -> Long,
    private val random: Random = Random.Default,
) {
    fun originate(
        payload: ByteArray,
        destination: NodeId = NodeId.BROADCAST,
        priority: Priority = Priority.NORMAL,
        type: PacketType = PacketType.MESSAGE,
        ttl: Int = Packet.DEFAULT_TTL,
    ): Packet {
        val packet = Packet(
            id = MessageId(random.nextLong()),
            type = type,
            priority = priority,
            origin = self,
            destination = destination,
            ttl = ttl,
            createdAt = clock(),
            path = emptyList(),
            payload = payload,
        )
        // Mark our own message seen so echoes from neighbours are dropped.
        seen.markIfNew(packet.id)
        return packet
    }

    /** Marks ids handled before a restart as seen, so a neighbour's replay isn't delivered twice. */
    fun remember(ids: Iterable<MessageId>) = ids.forEach { seen.markIfNew(it) }

    fun onReceive(packet: Packet): RouteDecision {
        if (packet.origin == self) return RouteDecision.Drop(DropReason.OWN_PACKET)
        // Backs up the seen cache: catches a loop even after the id has expired from it.
        if (self in packet.path) return RouteDecision.Drop(DropReason.LOOP)
        val forMe = packet.destination == self
        val forward = if (!forMe && packet.ttl > 1) {
            packet.copy(ttl = packet.ttl - 1, path = packet.path + self)
        } else {
            null
        }
        return when (seen.offer(packet.id, packet.ttl)) {
            Seen.NEW -> RouteDecision.Accept(deliver = forMe || packet.destination == NodeId.BROADCAST, forward)
            // The first copy came the long way; this one can still reach nodes that one couldn't.
            Seen.BETTER -> if (forward != null) {
                RouteDecision.Accept(deliver = false, forward = forward, upgrade = true)
            } else {
                RouteDecision.Drop(DropReason.DUPLICATE)
            }
            Seen.DUPLICATE -> RouteDecision.Drop(DropReason.DUPLICATE)
        }
    }
}
