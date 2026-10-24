package dev.leyline.sim

import kotlin.random.Random

/** A static layout: nodes 1..[size] and the undirected links between them. */
class Topology(val size: Int, val edges: List<Pair<Int, Int>>) {
    private val adjacency: Map<Int, List<Int>> = buildMap<Int, MutableList<Int>> {
        for (i in 1..this@Topology.size) put(i, mutableListOf())
        for ((a, b) in edges) {
            getValue(a) += b
            getValue(b) += a
        }
    }

    /** Hop distance from [origin] to every node it can reach, itself at 0. */
    fun hopsFrom(origin: Int): Map<Int, Int> {
        val hops = linkedMapOf(origin to 0)
        val frontier = ArrayDeque(listOf(origin))
        while (frontier.isNotEmpty()) {
            val node = frontier.removeFirst()
            for (next in adjacency.getValue(node)) {
                if (next !in hops) {
                    hops[next] = hops.getValue(node) + 1
                    frontier.addLast(next)
                }
            }
        }
        return hops
    }

    /** Nodes a flood from [origin] can reach before its [ttl] runs out. */
    fun withinTtl(origin: Int, ttl: Int): Set<Int> = hopsFrom(origin).filter { (node, hops) -> node != origin && hops <= ttl }.keys

    val isConnected: Boolean get() = hopsFrom(1).size == size

    val diameter: Int get() = (1..size).maxOf { hopsFrom(it).values.max() }

    val meanDegree: Double get() = 2.0 * edges.size / size

    fun applyTo(network: VirtualNetwork, profile: LinkProfile = LinkProfile()) =
        edges.forEach { (a, b) -> network.connect(a, b, profile) }

    companion object {
        fun line(n: Int) = Topology(n, (1 until n).map { it to it + 1 })

        fun ring(n: Int) = Topology(n, (1 until n).map { it to it + 1 } + (n to 1))

        /** Row-major: node (x, y) is `y * w + x + 1`. */
        fun grid(w: Int, h: Int): Topology {
            fun id(x: Int, y: Int) = y * w + x + 1
            val edges = buildList {
                for (y in 0 until h) for (x in 0 until w) {
                    if (x + 1 < w) add(id(x, y) to id(x + 1, y))
                    if (y + 1 < h) add(id(x, y) to id(x, y + 1))
                }
            }
            return Topology(w * h, edges)
        }

        fun full(n: Int) = Topology(n, buildList { for (i in 1..n) for (j in i + 1..n) add(i to j) })

        /**
         * [n] nodes dropped uniformly in a [side] × [side] m square, linked when closer
         * than [range]. Redrawn until connected, so "within TTL" is well defined.
         */
        fun randomGeometric(n: Int, side: Double, range: Double, random: Random): Topology {
            repeat(1_000) {
                val points = List(n) { Point(random.nextDouble() * side, random.nextDouble() * side) }
                val edges = buildList {
                    for (i in 0 until n) for (j in i + 1 until n) {
                        if (points[i].distanceTo(points[j]) < range) add(i + 1 to j + 1)
                    }
                }
                val topology = Topology(n, edges)
                if (topology.isConnected) return topology
            }
            error("no connected layout for $n nodes, side $side m, range $range m")
        }
    }
}
