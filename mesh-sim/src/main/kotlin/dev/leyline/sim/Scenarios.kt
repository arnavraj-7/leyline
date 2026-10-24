package dev.leyline.sim

import dev.leyline.mesh.Location
import dev.leyline.mesh.MeshConfig
import dev.leyline.mesh.MessageBody
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.PacketCodec
import dev.leyline.mesh.Priority
import kotlin.random.Random

/** Every scenario by name, in report order. Each is a pure function of its seed. */
val SCENARIOS: Map<String, (Long) -> ScenarioResult> = linkedMapOf(
    "line3" to ::line3,
    "storm" to ::storm,
    "priority" to ::priority,
    "dtn-mule" to ::dtnMule,
    "dtn-walkers" to ::dtnWalkers,
    "random-geometric" to ::randomGeometric,
    "churn" to ::churn,
    "lossy" to ::lossy,
    "worst-mtu" to ::worstMtu,
)

/** A: SOS from A crosses B to C; receipts come back to A. */
fun line3(seed: Long): ScenarioResult {
    val notes = mutableListOf("Line A(1)–B(2)–C(3), 180 B frames, default MeshConfig (TTL 4).")
    val runs = Stack.entries.associate { stack ->
        staticNetwork(seed, stack, Topology.line(3)).use { net ->
            val sos = net.send(1, sosBody("SOS: ankle broken, behind the library, can't walk"), Priority.SOS)
            net.runFor(10_000)
            if (stack == Stack.LEYLINE) {
                val sentAt = net.recorder.sent.getValue(sos.id).at
                net.recorder.delivery(sos.id, NodeId(3))?.let {
                    notes += "Leyline: C got the SOS after ${ms(it.at - sentAt)}, ${hops(it.hops)}."
                }
                val receipts = net.recorder.receiptsFor(sos.id).entries
                    .joinToString { (by, r) -> "node ${by.value} after ${ms(r.at - sentAt)} (${hops(r.hops)})" }
                notes += "Leyline receipts at A: ${receipts.ifEmpty { "none" }}."
            }
            stack.label to net.summarize(stack.label)
        }
    }
    return ScenarioResult("line3", "A–B–C SOS over two hops", notes, listOf(runTable(runs.values)), runs)
}

/** B: the PS's "4+ node storm": 8 nodes all in range, everyone bursts at once. */
fun storm(seed: Long): ScenarioResult {
    val topology = Topology.full(8)
    val runs = Stack.entries.associate { stack ->
        staticNetwork(seed, stack, topology).use { net ->
            val workload = Random(seed)
            val start = net.now
            for (node in 1..8) repeat(20) { k ->
                net.sendAt(start + workload.nextLong(10_000), node, chat("storm $node/$k: water rising at the east gate"))
            }
            net.runFor(70_000)
            stack.label to net.summarize(stack.label)
        }
    }
    val (smart, naive) = runs.getValue(Stack.LEYLINE.label) to runs.getValue(Stack.NAIVE.label)
    val notes = listOf(
        "Full mesh of 8 (28 links), 180 B frames, TTL 4. Each node sends 20 NORMAL broadcasts at random times in 10 s: 160 messages, 1120 (message, receiver) pairs. 70 s run.",
        "Naive flooding writes at most 160 × (7 + 7 × 6) = 7840 packets; a little less when a relay's first copy came the long way with TTL 1 left.",
        "Leyline wrote ${smart.writes} packets, ${percent(smart.writes.toDouble() / naive.writes)} of naive's ${naive.writes}.",
    )
    return ScenarioResult("storm", "8-node broadcast storm", notes, listOf(runTable(runs.values)), runs)
}

/** C: a relay drowning in chat must still pass an SOS straight through. */
fun priority(seed: Long): ScenarioResult {
    // Feeders 1-3 and SOS sender 6 all hang off relay 4; sink 5 sits behind it.
    val topology = Topology(6, listOf(1 to 4, 2 to 4, 3 to 4, 6 to 4, 4 to 5))
    val sink = setOf(NodeId(5))
    val runs = LinkedHashMap<String, RunResult>()
    val rows = listOf(100, 300).flatMap { perFeeder ->
        Stack.entries.map { stack ->
            staticNetwork(seed, stack, topology).use { net ->
                val start = net.now
                for (feeder in 1..3) repeat(perFeeder) { k ->
                    net.sendAt(start + k * 1_000L / perFeeder, feeder, chat("feeder $feeder status $k: all fine here, nothing urgent to report, just chatting"))
                }
                net.sendAt(start + 3_000, 6, sosBody("SOS: fire on floor 2, stairwell blocked"), Priority.SOS)
                net.runFor(180_000)
                val label = "${stack.label}, ${3 * perFeeder} NORMAL"
                val all = net.summarize(label).also { runs[label] = it }
                val atSink = net.summarize(label, receivers = sink)
                listOf(
                    "${3 * perFeeder}",
                    stack.label,
                    ms(atSink.sosLatency.max),
                    atSink.normalLatency.format(),
                    "${atSink.normalLatency.count}/${3 * perFeeder}",
                    all.sosLatency.format(),
                    all.queueDrops.toString(),
                    all.writes.toString(),
                )
            }
        }
    }
    val header = listOf(
        "NORMAL load", "Run", "SOS latency at sink", "NORMAL p50 / p95 / max at sink", "NORMAL delivered at sink",
        "SOS p50 / p95 / max (all nodes)", "Queue drops", "Packet writes",
    )
    val notes = listOf(
        "Star: feeders 1–3 and SOS sender 6 around relay 4; sink 5 behind relay 4. Default MeshConfig (queue 256 per neighbour, resync 15 s).",
        "Feeders send 300 (or 900) NORMAL broadcasts in the first second; node 6 sends one SOS at 3 s, with hundreds of packets queued at relay 4. 180 s run.",
        "At 900 the relay's per-neighbour queues overflow (queue drops); those packets stay in the outbox and go out on the next resync.",
    )
    return ScenarioResult("priority", "SOS through a busy relay", notes, listOf(Table(header, rows)), runs)
}

/** E: a realistic static deployment: ~30 phones scattered over a campus block. */
fun randomGeometric(seed: Long): ScenarioResult {
    val random = Random(seed)
    val topology = Topology.randomGeometric(30, side = 400.0, range = 100.0, random)
    val origins = (1..30).shuffled(random).take(5)
    data class Variant(val stack: Stack, val ttl: Int, val priority: Priority = Priority.NORMAL) {
        val label = "${stack.label}, TTL $ttl" + if (priority == Priority.SOS) ", SOS + receipts" else ""
    }
    val variants = listOf(
        Variant(Stack.LEYLINE, 4), Variant(Stack.NAIVE, 4),
        Variant(Stack.LEYLINE, 7), Variant(Stack.NAIVE, 7),
        Variant(Stack.LEYLINE, 7, Priority.SOS),
    )
    val runs = variants.associate { v ->
        staticNetwork(seed, v.stack, topology, MeshConfig(defaultTtl = v.ttl)).use { net ->
            val start = net.now
            origins.forEachIndexed { i, origin ->
                net.sendAt(start + i * 2_000L, origin, chat("report $i from node $origin"), v.priority, expected = topology.withinTtl(origin, v.ttl))
            }
            net.runFor(60_000)
            v.label to net.summarize(v.label)
        }
    }
    val notes = listOf(
        "30 nodes in a 400 × 400 m square, linked under 100 m: ${topology.edges.size} links, mean degree ${fmt("%.1f", topology.meanDegree)}, diameter ${topology.diameter} hops.",
        "5 broadcasts from nodes ${origins.joinToString()}, 2 s apart, 60 s run. Expected receivers = nodes within TTL hops of the origin, so 100% is the target for every row.",
        "Custom MeshConfig: defaultTtl 4 or 7 as labelled; everything else default.",
    )
    return ScenarioResult("random-geometric", "30 scattered nodes", notes, listOf(runTable(runs.values)), runs)
}

/** G: frame loss on a 4-hop line; Leyline's resync should recover what naive loses. */
fun lossy(seed: Long): ScenarioResult {
    val text = "Status from the shelter: 14 people, two injured, water for one day. ".repeat(3).take(200)
    val runs = LinkedHashMap<String, RunResult>()
    val rows = listOf(0.05, 0.15, 0.30).flatMap { loss ->
        Stack.entries.map { stack ->
            staticNetwork(seed, stack, Topology.line(5), profile = LinkProfile(frameLoss = loss)).use { net ->
                val start = net.now
                repeat(10) { k -> net.sendAt(start + k * 2_000L, 1, chat("$k $text")) }
                net.runFor(300_000)
                val label = "${stack.label}, ${percent(loss)} loss"
                val result = net.summarize(label).also { runs[label] = it }
                val quick = net.summarize(label, within = 10_000)
                listOf(
                    percent(loss), stack.label, result.delivery(), percent(quick.deliveryRatio), result.latency.format(),
                    result.frames.toString(), result.lostFrames.toString(), result.failedWrites.toString(),
                )
            }
        }
    }
    val header = listOf(
        "Frame loss", "Run", "Delivered (5 min)", "Delivered ≤ 10 s", "Latency p50 / p95 / max",
        "Frames", "Lost frames", "Failed writes",
    )
    val notes = listOf(
        "Line of 5 (4 hops), TTL 4, 180 B frames. Node 1 sends 10 NORMAL broadcasts of ~210 B (2 frames per packet), 2 s apart. 5 min run.",
        "A lost frame fails the whole packet write. Leyline retries on its 15 s resync; naive never retries.",
    )
    return ScenarioResult("lossy", "Frame loss on a line of 5", notes, listOf(Table(header, rows)), runs)
}

/** H: the largest SOS over the smallest MTU BLE allows. */
fun worstMtu(seed: Long): ScenarioResult {
    val body = sosBody("SOS ".repeat(70)) // 280 chars, the PS limit, plus location and name
    val runs = LinkedHashMap<String, RunResult>()
    val rows = listOf(20, 180).flatMap { frameSize ->
        Stack.entries.map { stack ->
            staticNetwork(seed, stack, Topology.line(4), profile = LinkProfile(frameSize = frameSize)).use { net ->
                val sos = net.send(1, body, Priority.SOS)
                net.runFor(60_000)
                val label = "${stack.label}, $frameSize B frames"
                val result = net.summarize(label).also { runs[label] = it }
                val sentAt = net.recorder.sent.getValue(sos.id).at
                val toEnd = net.recorder.delivery(sos.id, NodeId(4))?.let { it.at - sentAt }
                val lastReceipt = net.recorder.receiptsFor(sos.id).values.maxOfOrNull { it.at - sentAt }
                val bytes = PacketCodec.encodedSize(sos)
                val chunk = frameSize - 5
                listOf(
                    "$frameSize B", stack.label, "$bytes B", "${(bytes + chunk - 1) / chunk}", result.delivery(),
                    ms(toEnd), ms(lastReceipt), result.frames.toString(),
                )
            }
        }
    }
    val header = listOf(
        "Frame size", "Run", "Packet (1st hop)", "Frames per hop", "Delivered", "To node 4 (3 hops)",
        "Last receipt at origin", "Frames",
    )
    val notes = listOf("Line of 4, one 280-char SOS with location and sender name. Path grows 4 B per hop. Default MeshConfig.")
    return ScenarioResult("worst-mtu", "280-char SOS over 20-byte frames", notes, listOf(Table(header, rows)), runs)
}

internal fun staticNetwork(
    seed: Long,
    stack: Stack,
    topology: Topology,
    config: MeshConfig = MeshConfig(),
    profile: LinkProfile = LinkProfile(),
): VirtualNetwork {
    val net = VirtualNetwork(seed, profile)
    for (id in 1..topology.size) net.add(id, stack, config)
    topology.applyTo(net, profile)
    net.runFor(1_000) // let every link come up before traffic starts
    return net
}

internal fun chat(text: String): ByteArray = MessageBody(text).encode()

internal fun sosBody(text: String): ByteArray =
    MessageBody(text, Location.of(12.9716, 77.5946, 8f), senderName = "Asha").encode()
