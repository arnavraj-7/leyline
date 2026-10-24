package dev.leyline.sim

import dev.leyline.mesh.MeshEvent
import dev.leyline.mesh.MessageId
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Packet

/**
 * Ground truth of one run: what was sent, to whom it was meant to go, and when
 * each copy arrived. Nodes report here; nothing here feeds back into routing.
 */
class Recorder {
    class Sent(val packet: Packet, val at: Long, val expected: Set<NodeId>)

    class Arrival(val at: Long, val hops: Int)

    val sent = LinkedHashMap<MessageId, Sent>()
    private val deliveries = HashMap<MessageId, LinkedHashMap<NodeId, Arrival>>()
    private val receipts = HashMap<MessageId, LinkedHashMap<NodeId, Arrival>>()

    /** Times a node was handed a message it had already been given. Must stay 0. */
    var repeatDeliveries = 0
        private set

    val receiptCount: Int get() = receipts.values.sumOf { it.size }

    fun sent(packet: Packet, at: Long, expected: Set<NodeId>) {
        sent[packet.id] = Sent(packet, at, expected)
    }

    fun delivered(node: NodeId, packet: Packet, hops: Int, at: Long) {
        val byNode = deliveries.getOrPut(packet.id) { LinkedHashMap() }
        if (node in byNode) repeatDeliveries++ else byNode[node] = Arrival(at, hops)
    }

    fun record(node: NodeId, event: MeshEvent) {
        when (event) {
            is MeshEvent.Delivered -> delivered(node, event.packet, event.hops, event.receivedAt)
            is MeshEvent.Receipt ->
                receipts.getOrPut(event.messageId) { LinkedHashMap() }.putIfAbsent(event.by, Arrival(event.receivedAt, event.hops))
            is MeshEvent.Heard, is MeshEvent.Rejected, is MeshEvent.Dropped, is MeshEvent.Forwarded -> Unit
        }
    }

    fun delivery(id: MessageId, node: NodeId): Arrival? = deliveries[id]?.get(node)

    fun receiptsFor(id: MessageId): Map<NodeId, Arrival> = receipts[id].orEmpty()
}

/** Nearest-rank percentiles of a set of latencies, in ms; null fields when there were none. */
data class Percentiles(val count: Int, val p50: Long?, val p95: Long?, val max: Long?) {
    companion object {
        fun of(values: List<Long>): Percentiles {
            if (values.isEmpty()) return Percentiles(0, null, null, null)
            val sorted = values.sorted()
            fun rank(p: Double) = sorted[(Math.ceil(p * sorted.size).toInt() - 1).coerceIn(sorted.indices)]
            return Percentiles(sorted.size, rank(0.50), rank(0.95), sorted.last())
        }
    }
}

/** One run's numbers, as they go into the report tables. */
data class RunResult(
    val label: String,
    /** (message, intended receiver) pairs. */
    val expected: Int,
    val delivered: Int,
    val latency: Percentiles,
    val sosLatency: Percentiles,
    val normalLatency: Percentiles,
    val frames: Long,
    val lostFrames: Long,
    val writes: Long,
    val receiptWrites: Long,
    val failedWrites: Long,
    val redundant: Int,
    val malformed: Int,
    val queueDrops: Int,
    val receipts: Int,
    val repeatDeliveries: Int,
    val crashes: Int,
) {
    val deliveryRatio: Double get() = if (expected == 0) 1.0 else delivered.toDouble() / expected

    val writesPerDelivery: Double get() = if (delivered == 0) Double.NaN else writes.toDouble() / delivered
}
