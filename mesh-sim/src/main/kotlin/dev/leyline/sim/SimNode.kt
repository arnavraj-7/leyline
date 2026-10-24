package dev.leyline.sim

import dev.leyline.mesh.MeshNode
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Packet
import dev.leyline.mesh.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Which routing stack a simulated node runs. */
enum class Stack(val label: String) {
    LEYLINE("Leyline"),
    NAIVE("Naive flood"),
}

/** Per-node counters that both stacks can report. */
data class NodeCounters(
    /** Copies dropped by the router as already seen, own echoes or loops: wasted air time. */
    val redundant: Int,
    val malformed: Int,
    val queueDrops: Int,
)

/** What the scenarios need from a node, so Leyline and the baseline run the same scripts. */
interface SimNode {
    val id: NodeId
    val counters: NodeCounters

    fun start(scope: CoroutineScope)

    suspend fun send(body: ByteArray, destination: NodeId, priority: Priority): Packet
}

/** Leyline's real [MeshNode], with its events copied into the run's [Recorder]. */
class LeylineNode(val mesh: MeshNode, private val recorder: Recorder) : SimNode {
    override val id: NodeId get() = mesh.self

    override val counters: NodeCounters
        get() = mesh.stats.value.let {
            NodeCounters(redundant = it.duplicates + it.echoes + it.loops, malformed = it.malformed, queueDrops = it.queueDrops)
        }

    override fun start(scope: CoroutineScope) {
        // Subscribe before the node runs, or the first events would have no collector.
        scope.launch { mesh.events.collect { recorder.record(id, it) } }
        mesh.start(scope)
    }

    override suspend fun send(body: ByteArray, destination: NodeId, priority: Priority): Packet =
        mesh.send(body, destination, priority)
}
