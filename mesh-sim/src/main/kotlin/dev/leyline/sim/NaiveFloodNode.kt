package dev.leyline.sim

import dev.leyline.mesh.LinkEvent
import dev.leyline.mesh.MalformedPacketException
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Packet
import dev.leyline.mesh.PacketCodec
import dev.leyline.mesh.PacketType
import dev.leyline.mesh.Priority
import dev.leyline.mesh.RouteDecision
import dev.leyline.mesh.Router
import dev.leyline.mesh.SeenCache
import dev.leyline.mesh.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * The baseline for "naive vs smart": the same [Router] (dedup + TTL) as Leyline
 * and nothing else. Every accepted packet goes straight out to every current
 * neighbour except the one it came from. No outbox, so nothing is carried across
 * a gap or retried after a failed write; no holder tracking or jitter, so every
 * neighbour gets every copy; one FIFO queue per neighbour, so an SOS waits
 * behind chat. Sends are queued per neighbour, as Nordic's request queue would.
 */
class NaiveFloodNode(
    override val id: NodeId,
    private val transport: Transport,
    private val recorder: Recorder,
    private val clock: () -> Long,
    random: Random,
    private val ttl: Int,
) : SimNode {
    private val router = Router(id, SeenCache(10_000, 24 * 60 * 60_000L, clock), clock, random)
    private val queues = LinkedHashMap<NodeId, Channel<ByteArray>>()
    private lateinit var scope: CoroutineScope
    private var redundant = 0
    private var malformed = 0

    override val counters: NodeCounters get() = NodeCounters(redundant, malformed, queueDrops = 0)

    override fun start(scope: CoroutineScope) {
        scope.launch {
            this@NaiveFloodNode.scope = this
            transport.events.collect { event ->
                when (event) {
                    is LinkEvent.Connected -> open(event.neighbour)
                    // Cancel, not close: whatever was queued for that neighbour is simply lost.
                    is LinkEvent.Disconnected -> queues.remove(event.neighbour)?.cancel()
                    is LinkEvent.Received -> onReceived(event.neighbour, event.bytes)
                }
            }
        }
    }

    override suspend fun send(body: ByteArray, destination: NodeId, priority: Priority): Packet =
        router.originate(body, destination, priority, PacketType.MESSAGE, ttl).also { flood(it, except = null) }

    private fun onReceived(from: NodeId, bytes: ByteArray) {
        val packet = try {
            PacketCodec.decode(bytes)
        } catch (e: MalformedPacketException) {
            malformed++
            return
        }
        when (val decision = router.onReceive(packet)) {
            is RouteDecision.Drop -> redundant++
            is RouteDecision.Accept -> {
                if (decision.deliver) recorder.delivered(id, packet, packet.relayCount + 1, clock())
                decision.forward?.let { flood(it, except = from) }
            }
        }
    }

    private fun flood(packet: Packet, except: NodeId?) {
        val bytes = PacketCodec.encode(packet)
        queues.forEach { (neighbour, queue) -> if (neighbour != except) queue.trySend(bytes) }
    }

    private fun open(neighbour: NodeId) {
        if (neighbour in queues) return
        val queue = Channel<ByteArray>(Channel.UNLIMITED)
        queues[neighbour] = queue
        scope.launch { for (bytes in queue) transport.send(neighbour, bytes) }
    }
}
