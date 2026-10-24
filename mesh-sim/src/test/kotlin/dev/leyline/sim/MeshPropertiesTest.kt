package dev.leyline.sim

import dev.leyline.mesh.MeshConfig
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Priority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Claims the README makes about Leyline, checked against the simulator. */
class MeshPropertiesTest {
    @Test
    fun `static connected topologies deliver everything within TTL`() {
        val topologies = mapOf(
            "line 5" to Topology.line(5),
            "ring 8" to Topology.ring(8),
            "grid 3x3" to Topology.grid(3, 3),
            "full 6" to Topology.full(6),
        )
        for ((name, topology) in topologies) {
            staticNetwork(1, Stack.LEYLINE, topology).use { net ->
                for (origin in listOf(1, topology.size)) {
                    net.send(origin, chat("hello from $origin"), expected = topology.withinTtl(origin, 4))
                    net.runFor(2_000)
                }
                net.send(2, sosBody("SOS"), Priority.SOS, expected = topology.withinTtl(2, 4))
                net.runFor(10_000)
                val result = net.summarize(name)
                assertEquals(result.expected, result.delivered, "$name: ${result.delivery()}")
            }
        }
    }

    /**
     * Random campus-like graphs are where flooding races bite: the first copy to
     * reach a node often came the long way. Every node within TTL hops must still
     * get every message, at the TTL edge too (regression for the TTL-upgrade and
     * TTL-aware-holder fixes).
     */
    @Test
    fun `random graphs deliver to every node within TTL, at the edge too`() {
        for (seed in 1L..5L) {
            for (ttl in listOf(4, 7)) {
                val random = kotlin.random.Random(seed)
                val topology = Topology.randomGeometric(30, side = 400.0, range = 100.0, random)
                staticNetwork(seed, Stack.LEYLINE, topology, MeshConfig(defaultTtl = ttl)).use { net ->
                    val origins = (1..30).shuffled(random).take(5)
                    origins.forEach { origin ->
                        net.send(origin, chat("from $origin"), expected = topology.withinTtl(origin, ttl))
                        net.runFor(2_000)
                    }
                    net.runFor(30_000)
                    for ((id, sent) in net.recorder.sent) {
                        for (node in sent.expected) {
                            assertNotNull(net.recorder.delivery(id, node), "seed $seed ttl $ttl: $node missed ${sent.packet.origin}")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `no scenario ever delivers a message twice to the same node or crashes a node`() {
        for ((name, result) in allScenarios) {
            for (run in result.runs.values) {
                assertEquals(0, run.repeatDeliveries, "$name / ${run.label}")
                assertEquals(0, run.crashes, "$name / ${run.label}")
                assertEquals(0, run.malformed, "$name / ${run.label}")
            }
        }
    }

    @Test
    fun `TTL bounds how many hops a message travels, even after resyncs`() {
        for (stack in Stack.entries) {
            staticNetwork(1, stack, Topology.line(8), MeshConfig(defaultTtl = 3)).use { net ->
                val sent = net.send(1, chat("short range"))
                net.runFor(120_000) // eight resync rounds
                for (node in 2..4) assertEquals(node - 1, net.recorder.delivery(sent.id, NodeId(node))?.hops, "$stack node $node")
                for (node in 5..8) assertNull(net.recorder.delivery(sent.id, NodeId(node)), "$stack node $node")
            }
        }
    }

    @Test
    fun `SOS overtakes queued chat at a busy relay`() {
        for (load in listOf(300, 900)) {
            val run = priority.runs.getValue("Leyline, $load NORMAL")
            val sos = checkNotNull(run.sosLatency.max)
            val normal = checkNotNull(run.normalLatency.p50)
            assertTrue(sos < normal, "load $load: SOS max $sos ms vs NORMAL p50 $normal ms")
        }
    }

    @Test
    fun `a mule carries the SOS across the gap but naive flooding cannot`() {
        val leyline = dtnMule.runs.getValue(Stack.LEYLINE.label)
        val naive = dtnMule.runs.getValue(Stack.NAIVE.label)
        assertEquals(1.0, leyline.deliveryRatio, leyline.delivery())
        // Naive reaches only the three other nodes of cluster 1.
        assertEquals(3, naive.delivered, naive.delivery())
    }

    @Test
    fun `the storm costs fewer writes than naive flooding at full delivery`() {
        val leyline = storm.runs.getValue(Stack.LEYLINE.label)
        val naive = storm.runs.getValue(Stack.NAIVE.label)
        assertEquals(1.0, leyline.deliveryRatio)
        assertTrue(leyline.writes < naive.writes, "Leyline ${leyline.writes} vs naive ${naive.writes}")
    }

    @Test
    fun `15 percent frame loss still delivers at least 95 percent given resync time`() {
        val run = lossy.runs.getValue("Leyline, 15.0% loss")
        assertTrue(run.deliveryRatio >= 0.95, run.delivery())
    }

    /**
     * Reproduces a mesh-core bug: the first copy of a packet wins even when it came
     * the long way with little TTL left, and the later short-path copy is dropped
     * as a duplicate. Here O→X is a slow 20-byte link, so X first hears the SOS via
     * Y with TTL 1, can't forward it, and T (two hops from O) never gets it.
     * Enable once Router re-forwards a duplicate that carries more TTL.
     */
    @Test
    fun `a node within TTL is reached even if a long-path copy arrives first`() {
        // O = 1, X = 2, T = 3, Y = 4
        VirtualNetwork(1).use { net ->
            (1..4).forEach { net.add(it, config = MeshConfig(defaultTtl = 2)) }
            net.connect(1, 2, LinkProfile(frameSize = 20))
            net.connect(2, 3)
            net.connect(1, 4)
            net.connect(4, 2)
            net.runFor(1_000)
            val sos = net.send(1, sosBody("SOS ".repeat(70)), Priority.SOS)
            net.runFor(60_000)
            assertEquals(2, net.recorder.delivery(sos.id, NodeId(3))?.hops)
        }
    }

    /**
     * Reproduces a mesh-core bug: once the seen cache evicts an id by capacity,
     * a neighbour still carrying that packet replays it and it is delivered again.
     * Capacity 20 stands in for the default 10 000 being outrun by a busy mesh
     * (every SOS also creates one receipt id per receiver).
     */
    @Test
    fun `a packet replayed after seen-cache eviction is not delivered twice`() {
        VirtualNetwork(1).use { net ->
            (1..3).forEach { net.add(it, config = MeshConfig(seenCapacity = 20)) }
            net.connect(1, 2)
            net.runFor(1_000)
            net.send(1, sosBody("SOS"), Priority.SOS)
            repeat(30) {
                net.send(1, chat("filler $it"))
                net.runFor(200)
            }
            net.runFor(2_000)
            net.disconnect(1, 2)
            net.connect(1, 3) // C picks everything up from A
            net.runFor(5_000)
            net.disconnect(1, 3)
            net.connect(2, 3) // and replays it to B, which has forgotten the old ids
            net.runFor(20_000)
            assertEquals(0, net.recorder.repeatDeliveries)
        }
    }

    private companion object {
        val allScenarios: Map<String, ScenarioResult> by lazy { SCENARIOS.mapValues { (_, run) -> run(1) } }
        val priority get() = allScenarios.getValue("priority")
        val dtnMule get() = allScenarios.getValue("dtn-mule")
        val storm get() = allScenarios.getValue("storm")
        val lossy get() = allScenarios.getValue("lossy")
    }
}
