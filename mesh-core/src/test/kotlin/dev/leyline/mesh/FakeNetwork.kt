@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package dev.leyline.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope

/**
 * Tiny in-memory network for MeshNode tests: links are toggled by hand and
 * every write takes [latencyMillis] of virtual time. The full simulator with
 * loss and mobility lives in mesh-sim.
 */
class FakeNetwork(private val test: TestScope, private val latencyMillis: Long = 10) {
    private val transports = HashMap<NodeId, FakeTransport>()
    private val links = HashSet<Pair<NodeId, NodeId>>()
    val nodes = HashMap<NodeId, TestNode>()

    /** Packets written per sender, to measure flooding cost. */
    val writes = HashMap<NodeId, Int>()

    /** Every backlog hint, as (sender, bytes waiting). */
    val backlogs = mutableListOf<Pair<NodeId, Int>>()

    /** The next n writes fail as if the radio timed out, while the link stays up. */
    var failWrites = 0

    inner class FakeTransport(private val self: NodeId) : Transport {
        val inbox = Channel<LinkEvent>(Channel.UNLIMITED)
        override val events = inbox.receiveAsFlow()

        override suspend fun send(neighbour: NodeId, bytes: ByteArray): Boolean {
            if (!linked(self, neighbour)) return false
            delay(latencyMillis)
            if (!linked(self, neighbour)) return false
            if (failWrites > 0) {
                failWrites--
                return false
            }
            writes[self] = (writes[self] ?: 0) + 1
            transports.getValue(neighbour).inbox.send(LinkEvent.Received(self, bytes))
            return true
        }

        override fun backlog(neighbour: NodeId, bytes: Int) {
            backlogs += self to bytes
        }
    }

    class TestNode(val node: MeshNode, val outbox: InMemoryOutbox) {
        val id: NodeId get() = node.self
        val events = mutableListOf<MeshEvent>()
        val delivered get() = events.filterIsInstance<MeshEvent.Delivered>()
        val receipts get() = events.filterIsInstance<MeshEvent.Receipt>()
    }

    fun add(
        id: Int,
        config: MeshConfig = MeshConfig(),
        knownIds: List<MessageId> = emptyList(),
        scope: CoroutineScope = test.backgroundScope,
    ): TestNode = add(NodeId(id), config, knownIds, scope, signer = null)

    /** A node that signs: its id comes from its key, so link it with [link] by [TestNode.id]. */
    fun addSigned(signer: SoftwareSigner = SoftwareSigner(), config: MeshConfig = MeshConfig(requireSignatures = true)) =
        add(signer.nodeId, config, emptyList(), test.backgroundScope, signer)

    private fun add(
        nodeId: NodeId,
        config: MeshConfig,
        knownIds: List<MessageId>,
        scope: CoroutineScope,
        signer: PacketSigner?,
    ): TestNode {
        val transport = FakeTransport(nodeId).also { transports[nodeId] = it }
        val outbox = InMemoryOutbox()
        val node = MeshNode(
            self = nodeId,
            transport = transport,
            outbox = outbox,
            clock = { test.testScheduler.currentTime },
            config = config,
            random = kotlin.random.Random(nodeId.value),
            signer = signer,
        )
        node.displayName = "node ${nodeId.value}"
        val testNode = TestNode(node, outbox)
        node.start(scope, knownIds)
        scope.launch { node.events.collect { testNode.events += it } }
        nodes[nodeId] = testNode
        test.testScheduler.runCurrent()
        return testNode
    }

    fun link(a: Int, b: Int) = link(NodeId(a), NodeId(b))

    fun link(x: NodeId, y: NodeId) {
        if (links.add(key(x, y))) {
            transports.getValue(x).inbox.trySend(LinkEvent.Connected(y))
            transports.getValue(y).inbox.trySend(LinkEvent.Connected(x))
        }
    }

    fun unlink(a: Int, b: Int) {
        val (x, y) = NodeId(a) to NodeId(b)
        if (links.remove(key(x, y))) {
            transports.getValue(x).inbox.trySend(LinkEvent.Disconnected(y))
            transports.getValue(y).inbox.trySend(LinkEvent.Disconnected(x))
        }
    }

    fun inject(to: Int, from: Int, bytes: ByteArray) = inject(NodeId(to), NodeId(from), bytes)

    fun inject(to: NodeId, from: NodeId, bytes: ByteArray) {
        transports.getValue(to).inbox.trySend(LinkEvent.Received(from, bytes))
    }

    /** Lets in-flight traffic finish: well past jitter + latency, short of a resync. */
    fun settle() {
        test.testScheduler.advanceTimeBy(2_000)
        test.testScheduler.runCurrent()
    }

    private fun linked(a: NodeId, b: NodeId) = key(a, b) in links

    private fun key(a: NodeId, b: NodeId) = if (a.value < b.value) a to b else b to a
}
