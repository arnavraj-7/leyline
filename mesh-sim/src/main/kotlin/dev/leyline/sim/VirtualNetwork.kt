@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.leyline.sim

import dev.leyline.mesh.InMemoryOutbox
import dev.leyline.mesh.LinkEvent
import dev.leyline.mesh.LinkFrameCodec
import dev.leyline.mesh.MeshConfig
import dev.leyline.mesh.MeshNode
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Packet
import dev.leyline.mesh.PacketType
import dev.leyline.mesh.Priority
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.random.Random

/**
 * An in-memory BLE mesh on virtual time.
 *
 * Every node runs on one [StandardTestDispatcher], so `delay` costs no wall time:
 * an hour of traffic runs in seconds, and a run depends only on [seed] because
 * the scheduler orders same-time work deterministically. Links carry real
 * [LinkFrameCodec] frames into real reassemblers, so chunking, MTU and drops in
 * the middle of a transfer are exercised rather than assumed.
 *
 * Node ids are small ints (node `i` is `NodeId(i)`) to keep scenarios readable.
 */
class VirtualNetwork(val seed: Long, private val defaultProfile: LinkProfile = LinkProfile()) : AutoCloseable {
    private val scheduler = TestCoroutineScheduler()
    private val crashes = mutableListOf<Throwable>()

    /** Owns every node and driver; [close] cancels MeshNode's endless maintenance loops with it. */
    val scope = CoroutineScope(
        StandardTestDispatcher(scheduler) + SupervisorJob() + CoroutineExceptionHandler { _, e -> crashes += e },
    )

    val clock: () -> Long = { scheduler.currentTime }
    val now: Long get() = scheduler.currentTime
    val recorder = Recorder()
    val traffic = Traffic()

    /** Uncaught exceptions from inside nodes. A crashed MeshNode stops silently, so this must stay empty. */
    val failures: List<Throwable> get() = crashes

    private val radio = Random(seed * 31 + 7)
    private val transports = LinkedHashMap<NodeId, SimTransport>()
    private val _nodes = LinkedHashMap<NodeId, SimNode>()
    val nodes: Map<NodeId, SimNode> get() = _nodes
    private val links = HashMap<Pair<NodeId, NodeId>, SimLink>()

    fun add(id: Int, stack: Stack = Stack.LEYLINE, config: MeshConfig = MeshConfig()): SimNode {
        val nodeId = NodeId(id)
        require(nodeId !in _nodes) { "node $id already exists" }
        val transport = SimTransport(nodeId, this).also { transports[nodeId] = it }
        val random = Random(seed * 7919 + id)
        val node = when (stack) {
            Stack.LEYLINE -> LeylineNode(MeshNode(nodeId, transport, InMemoryOutbox(), clock, config, random), recorder)
            Stack.NAIVE -> NaiveFloodNode(nodeId, transport, recorder, clock, random, config.defaultTtl)
        }
        _nodes[nodeId] = node
        node.start(scope)
        scheduler.runCurrent()
        return node
    }

    fun connect(a: Int, b: Int, profile: LinkProfile = defaultProfile) {
        val (x, y) = NodeId(a) to NodeId(b)
        val key = key(x, y)
        if (key in links) return
        links[key] = SimLink(x, y, profile, clock)
        // The Hello exchange: one frame each way, already done when Connected fires.
        traffic.of(x).frames++
        traffic.of(y).frames++
        transports.getValue(x).inbox.trySend(LinkEvent.Connected(y))
        transports.getValue(y).inbox.trySend(LinkEvent.Connected(x))
    }

    fun disconnect(a: Int, b: Int) {
        val (x, y) = NodeId(a) to NodeId(b)
        val link = links.remove(key(x, y)) ?: return
        link.up = false
        transports.getValue(x).inbox.trySend(LinkEvent.Disconnected(y))
        transports.getValue(y).inbox.trySend(LinkEvent.Disconnected(x))
    }

    fun isLinked(a: Int, b: Int): Boolean = key(NodeId(a), NodeId(b)) in links

    val linkCount: Int get() = links.size

    /** Advances virtual time, running everything due up to and including the new instant. */
    fun runFor(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    /**
     * Originates a message now and returns it. [expected] defaults to every other
     * node for a broadcast and the destination for a unicast.
     */
    fun send(
        from: Int,
        body: ByteArray,
        priority: Priority = Priority.NORMAL,
        destination: NodeId = NodeId.BROADCAST,
        expected: Collection<Int>? = null,
    ): Packet {
        var packet: Packet? = null
        scope.launch { packet = originate(from, body, priority, destination, expected) }
        scheduler.runCurrent()
        return checkNotNull(packet) { "node $from did not originate" }
    }

    /** Like [send], at virtual time [time]; for workloads scripted ahead of the run. */
    fun sendAt(
        time: Long,
        from: Int,
        body: ByteArray,
        priority: Priority = Priority.NORMAL,
        destination: NodeId = NodeId.BROADCAST,
        expected: Collection<Int>? = null,
    ) {
        scope.launch {
            delay(time - now)
            originate(from, body, priority, destination, expected)
        }
    }

    /** Totals for every message sent so far, optionally narrowed to some receivers, send times or a deadline. */
    fun summarize(
        label: String,
        receivers: Set<NodeId>? = null,
        sentIn: LongRange? = null,
        within: Long? = null,
    ): RunResult {
        val all = mutableListOf<Long>()
        val sos = mutableListOf<Long>()
        val normal = mutableListOf<Long>()
        var expected = 0
        for (message in recorder.sent.values) {
            if (sentIn != null && message.at !in sentIn) continue
            for (node in message.expected) {
                if (receivers != null && node !in receivers) continue
                expected++
                val latency = (recorder.delivery(message.packet.id, node) ?: continue).at - message.at
                if (within != null && latency > within) continue
                all += latency
                (if (message.packet.priority == Priority.SOS) sos else normal) += latency
            }
        }
        val counters = _nodes.values.map { it.counters }
        return RunResult(
            label = label,
            expected = expected,
            delivered = all.size,
            latency = Percentiles.of(all),
            sosLatency = Percentiles.of(sos),
            normalLatency = Percentiles.of(normal),
            frames = traffic.frames,
            lostFrames = traffic.lostFrames,
            writes = traffic.writes,
            receiptWrites = traffic.receiptWrites,
            failedWrites = traffic.failedWrites,
            redundant = counters.sumOf { it.redundant },
            malformed = counters.sumOf { it.malformed },
            queueDrops = counters.sumOf { it.queueDrops },
            receipts = recorder.receiptCount,
            repeatDeliveries = recorder.repeatDeliveries,
            crashes = crashes.size,
        )
    }

    override fun close() = scope.cancel()

    /**
     * Writes one packet frame by frame. Frames share the connection with the other
     * direction (one GATT op in flight), each costs air time, and a lost frame or a
     * drop mid-transfer fails the write; the receiver then holds a partial transfer
     * until its reassembler times it out.
     */
    internal suspend fun write(from: NodeId, to: NodeId, bytes: ByteArray): Boolean {
        val link = links[key(from, to)] ?: return false
        val counts = traffic.of(from)
        val frames = LinkFrameCodec.split(bytes, link.nextTransferId(from), link.profile.frameSize)
        for (frame in frames) {
            val ok = link.gatt.withLock {
                delay(link.profile.frameTime(radio))
                if (!link.up) return@withLock false
                counts.frames++
                if (radio.nextDouble() < link.profile.frameLoss) {
                    counts.lostFrames++
                    return@withLock false
                }
                link.receive(to, frame)?.let { transports.getValue(to).inbox.trySend(LinkEvent.Received(from, it)) }
                true
            }
            if (!ok) {
                counts.failedWrites++
                return false
            }
        }
        counts.writes++
        // Byte 1 of the wire format is the packet type; receipts are worth counting apart.
        if (bytes[1].toInt() == PacketType.ACK.code) counts.receiptWrites++
        return true
    }

    private suspend fun originate(
        from: Int,
        body: ByteArray,
        priority: Priority,
        destination: NodeId,
        expected: Collection<Int>?,
    ): Packet {
        val origin = NodeId(from)
        val packet = _nodes.getValue(origin).send(body, destination, priority)
        val targets = expected?.map(::NodeId)?.toSet()
            ?: if (destination == NodeId.BROADCAST) _nodes.keys - origin else setOf(destination)
        recorder.sent(packet, now, targets)
        return packet
    }

    private fun key(a: NodeId, b: NodeId) = if (a.value < b.value) a to b else b to a
}
