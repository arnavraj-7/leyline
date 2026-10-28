package dev.leyline.app.mesh

import dev.leyline.mesh.Location
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Packet

/** An undirected radio link between two nodes, stored with the smaller id first. */
@ConsistentCopyVisibility
data class Edge private constructor(val a: NodeId, val b: NodeId) {
    companion object {
        fun of(x: NodeId, y: NodeId) = if (Integer.compareUnsigned(x.value, y.value) <= 0) Edge(x, y) else Edge(y, x)
    }
}

/**
 * What we know about the mesh beyond our own radio range.
 *
 * [hops] is the fewest radio links to each node we have heard from; [edges] are
 * links proven by packet paths (origin → relays → us), with when we last saw them.
 */
data class Topology(
    val self: NodeId,
    val direct: Set<NodeId> = emptySet(),
    val edges: Map<Edge, Long> = emptyMap(),
    val hops: Map<NodeId, Int> = emptyMap(),
    val names: Map<NodeId, String> = emptyMap(),
    /** Last GPS fix per node, ours included, from presence beacons and any message carrying one. */
    val positions: Map<NodeId, Location> = emptyMap(),
)

/**
 * Builds [Topology] from hop telemetry. Every packet records the relays it
 * crossed, so one SOS from three hops away already draws three links we could
 * never scan ourselves. Links not confirmed for [maxAgeMillis] fade out.
 *
 * Called from several coroutines, so every method is synchronized.
 */
class TopologyTracker(private val self: NodeId, private val maxAgeMillis: Long = 10 * 60_000) {
    private val edges = HashMap<Edge, Long>()
    private val hopsSeen = HashMap<NodeId, Pair<Int, Long>>()
    private val names = HashMap<NodeId, String>()
    private val positions = HashMap<NodeId, Triple<Location, Long, Long>>()
    private var direct = emptySet<NodeId>()

    @Synchronized
    fun onHeard(packet: Packet, now: Long) {
        val route = listOf(packet.origin) + packet.path + self
        route.zipWithNext().forEach { (x, y) -> if (x != y) edges[Edge.of(x, y)] = now }
        route.dropLast(1).forEachIndexed { index, node -> recordHops(node, route.size - 1 - index, now) }
    }

    @Synchronized
    fun onName(node: NodeId, name: String) {
        names[node] = name
    }

    /** [stamp] is the fix's time on the sender's clock: a carried, older beacon must not replace a newer one. */
    @Synchronized
    fun onPosition(node: NodeId, location: Location, now: Long, stamp: Long = now) {
        val previous = positions[node]
        if (previous == null || stamp >= previous.third) positions[node] = Triple(location, now, stamp)
    }

    @Synchronized
    fun onNeighbours(neighbours: Set<NodeId>, now: Long) {
        direct = neighbours
        neighbours.forEach {
            edges[Edge.of(self, it)] = now
            recordHops(it, 1, now)
        }
    }

    @Synchronized
    fun snapshot(now: Long): Topology {
        edges.values.removeAll { now - it > maxAgeMillis }
        hopsSeen.values.removeAll { now - it.second > maxAgeMillis }
        positions.values.removeAll { now - it.second > maxAgeMillis }
        return Topology(
            self = self,
            direct = direct,
            edges = edges.toMap(),
            hops = hopsSeen.mapValues { it.value.first } + direct.associateWith { 1 },
            names = names.toMap(),
            positions = positions.mapValues { it.value.first },
        )
    }

    private fun recordHops(node: NodeId, hops: Int, now: Long) {
        if (node == self || node == NodeId.BROADCAST) return
        val previous = hopsSeen[node]
        // Keep the shortest route, but let a stale one be replaced by a fresh longer one.
        if (previous == null || hops <= previous.first || now - previous.second > maxAgeMillis / 2) {
            hopsSeen[node] = hops to now
        }
    }
}

/** Packet helper for UI strings. */
val Packet.isBroadcast: Boolean get() = destination == NodeId.BROADCAST
