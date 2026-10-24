package dev.leyline.sim

import dev.leyline.mesh.LinkFrame
import dev.leyline.mesh.LinkFrameCodec
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Reassembler
import kotlinx.coroutines.sync.Mutex

/**
 * One BLE connection between two simulated nodes.
 *
 * A fresh instance is made every time the link comes up, and [up] goes false for
 * good when it drops. So a write that straddles a drop fails, and a reconnect
 * starts with new transfer ids and empty reassemblers, as a new GATT connection does.
 */
internal class SimLink(val a: NodeId, val b: NodeId, val profile: LinkProfile, clock: () -> Long) {
    var up = true

    /** One GATT operation in flight per connection, whichever side is writing. */
    val gatt = Mutex()

    private val transferIds = mutableMapOf(a to 0, b to 0)
    private val reassemblers = mapOf(a to Reassembler(clock), b to Reassembler(clock))

    fun nextTransferId(from: NodeId): Int = transferIds.getValue(from).also { transferIds[from] = it + 1 }

    /** Hands one frame to [to]'s reassembler; returns the packet bytes once the last frame lands. */
    fun receive(to: NodeId, frame: ByteArray): ByteArray? =
        reassemblers.getValue(to).accept(LinkFrameCodec.decode(frame) as LinkFrame.Data)
}

/** Radio cost per node: what the battery and the shared air time pay for. */
class Traffic {
    class Counts {
        /** Frames put on air, lost ones and Hellos included. */
        var frames = 0L
        var lostFrames = 0L

        /** Packets fully written and confirmed. */
        var writes = 0L

        /** The share of [writes] that were delivery receipts (ACK packets). */
        var receiptWrites = 0L
        var failedWrites = 0L
    }

    private val perNode = LinkedHashMap<NodeId, Counts>()

    fun of(node: NodeId): Counts = perNode.getOrPut(node) { Counts() }

    val frames: Long get() = perNode.values.sumOf { it.frames }
    val lostFrames: Long get() = perNode.values.sumOf { it.lostFrames }
    val writes: Long get() = perNode.values.sumOf { it.writes }
    val receiptWrites: Long get() = perNode.values.sumOf { it.receiptWrites }
    val failedWrites: Long get() = perNode.values.sumOf { it.failedWrites }
}
