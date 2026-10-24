package dev.leyline.sim

import dev.leyline.mesh.MeshConfig
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Priority
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ln
import kotlin.random.Random

/**
 * D: two clusters 1 km apart that never see each other; only a walker carries
 * messages between them. Store-and-forward is the whole difference here.
 */
fun dtnMule(seed: Long): ScenarioResult {
    val square = listOf(Point(0.0, 0.0), Point(20.0, 0.0), Point(0.0, 20.0), Point(20.0, 20.0))
    val clusterTwo = (5..8).map(::NodeId).toSet()
    val runs = LinkedHashMap<String, RunResult>()
    val rows = Stack.entries.map { stack ->
        VirtualNetwork(seed).use { net ->
            (1..9).forEach { net.add(it, stack) }
            val mobility = Mobility(net, range = 50.0)
            square.forEachIndexed { i, p ->
                mobility.place(i + 1, p)
                mobility.place(i + 5, Point(p.x + 1_000, p.y))
            }
            // Starts halfway, walks to cluster 1 first, then shuttles at 1.5 m/s.
            mobility.place(9, Point(500.0, 10.0), Patrol(listOf(Point(10.0, 10.0), Point(1_010.0, 10.0)), speed = 1.5))
            mobility.start()
            net.runFor(30_000)
            val sos = net.send(1, sosBody("SOS: 3 hikers stranded at the north camp"), Priority.SOS)
            val sentAt = net.now
            net.runFor(40 * 60_000L)
            val all = net.summarize(stack.label).also { runs[stack.label] = it }
            val far = net.summarize(stack.label, receivers = clusterTwo)
            val receipts = net.recorder.receiptsFor(sos.id).filterKeys { it in clusterTwo }
            listOf(
                stack.label,
                far.delivery(),
                all.delivery(),
                far.latency.format(),
                "${receipts.size}/4" + (receipts.values.maxOfOrNull { " by ${ms(it.at - sentAt)}" } ?: ""),
                all.writes.toString(),
            )
        }
    }
    val header = listOf(
        "Run", "Delivered to cluster 2", "Delivered (all 8)", "Latency to cluster 2 p50 / p95 / max",
        "Receipts from cluster 2", "Packet writes",
    )
    val notes = listOf(
        "Cluster 1 = nodes 1–4, cluster 2 = nodes 5–8, 1 km apart; radio range 50 m, links re-evaluated every 1 s.",
        "Mule (node 9) starts halfway and patrols between the clusters at 1.5 m/s (~11 min per crossing).",
        "Node 1 sends one SOS at 30 s, when the mule is out of everyone's range. 40 min run. Default MeshConfig.",
    )
    return ScenarioResult("dtn-mule", "Two clusters bridged only by a walking mule", notes, listOf(Table(header, rows)), runs)
}

/** D2: everyone walks (random waypoint) in an area too sparse for a connected mesh. */
fun dtnWalkers(seed: Long): ScenarioResult {
    data class Variant(val stack: Stack, val ttl: Int) {
        val label = "${stack.label}, TTL $ttl"
    }
    val variants = listOf(Variant(Stack.LEYLINE, 4), Variant(Stack.LEYLINE, 7), Variant(Stack.NAIVE, 7))
    var meanLinks = 0.0
    val runs = variants.associate { v ->
        VirtualNetwork(seed).use { net ->
            val n = 16
            (1..n).forEach { net.add(it, v.stack, MeshConfig(defaultTtl = v.ttl)) }
            val mobility = Mobility(net, range = 60.0)
            val layout = Random(seed)
            for (id in 1..n) {
                val walker = RandomWaypoint(600.0, 600.0, 0.5..1.5, maxPauseSeconds = 30.0, random = Random(seed * 100 + id))
                mobility.place(id, Point(layout.nextDouble() * 600, layout.nextDouble() * 600), walker)
            }
            mobility.start()
            var samples = 0
            var linkSum = 0
            net.scope.launch {
                while (true) {
                    linkSum += net.linkCount
                    samples++
                    delay(10_000)
                }
            }
            net.runFor(60_000)
            val workload = Random(seed + 1)
            repeat(5) { k -> net.sendAt(net.now + k * 60_000L, workload.nextInt(1, n + 1), chat("walker report $k")) }
            net.runFor(30 * 60_000L)
            meanLinks = linkSum.toDouble() / samples
            v.label to net.summarize(v.label)
        }
    }
    val notes = listOf(
        "16 walkers in 600 × 600 m, random waypoint at 0.5–1.5 m/s with pauses up to 30 s, range 60 m; ~${fmt("%.1f", meanLinks)} links up on average (of 120 possible pairs).",
        "5 NORMAL broadcasts from random nodes, one a minute from t = 60 s; 31 min run. NORMAL lifetime is 30 min, so this is the most a message can get.",
        "Custom MeshConfig: defaultTtl as labelled. TTL also caps DTN carry chains: each hand-over is a hop.",
    )
    return ScenarioResult("dtn-walkers", "Everyone moving, no stable path", notes, listOf(runTable(runs.values)), runs)
}

/** F: a grid whose links flap; how much still gets through, and how fast. */
fun churn(seed: Long): ScenarioResult {
    val topology = Topology.grid(4, 4)
    val config = MeshConfig(defaultTtl = 7)
    val bucketMillis = 2 * 60_000L
    val buckets = 5
    val runs = LinkedHashMap<String, RunResult>()
    val perBucket = Stack.entries.associateWith { stack ->
        staticNetwork(seed, stack, topology, config).use { net ->
            topology.edges.forEachIndexed { i, (a, b) ->
                val flap = Random(seed * 1_000 + i)
                net.scope.launch {
                    while (true) {
                        delay(exponential(flap, 30_000.0))
                        net.disconnect(a, b)
                        delay(exponential(flap, 10_000.0))
                        net.connect(a, b)
                    }
                }
            }
            val start = net.now
            val workload = Random(seed)
            repeat(60) { k -> net.sendAt(start + k * 10_000L, workload.nextInt(1, 17), chat("grid update $k")) }
            net.runFor(12 * 60_000L)
            runs[stack.label] = net.summarize(stack.label)
            (0 until buckets).map { b ->
                val window = start + b * bucketMillis until start + (b + 1) * bucketMillis
                net.summarize(stack.label, sentIn = window, within = 30_000) to net.summarize(stack.label, sentIn = window)
            }
        }
    }
    val rows = (0 until buckets).map { b ->
        listOf("${b * 2}–${b * 2 + 2} min") + Stack.entries.flatMap { stack ->
            val (quick, total) = perBucket.getValue(stack)[b]
            listOf(percent(quick.deliveryRatio), percent(total.deliveryRatio))
        }
    }
    val header = listOf("Sent during") + Stack.entries.flatMap { listOf("${it.label} ≤ 30 s", "${it.label} by end") }
    val notes = listOf(
        "4 × 4 grid (24 links, diameter 6). Every link flaps independently: up for exp(mean 30 s), down for exp(mean 10 s), so ~75% availability.",
        "One NORMAL broadcast every 10 s from a random node for 10 min; 12 min run. Custom MeshConfig: defaultTtl 7.",
    )
    return ScenarioResult("churn", "Flapping links on a grid", notes, listOf(Table(header, rows), runTable(runs.values)), runs)
}

private fun exponential(random: Random, mean: Double): Long = (-mean * ln(1 - random.nextDouble())).toLong().coerceAtLeast(1)
