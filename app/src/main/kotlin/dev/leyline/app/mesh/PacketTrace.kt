package dev.leyline.app.mesh

import dev.leyline.mesh.DropReason
import dev.leyline.mesh.MeshEvent
import dev.leyline.mesh.MessageBody
import dev.leyline.mesh.MessageId
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Packet
import dev.leyline.mesh.PacketType
import dev.leyline.mesh.Priority
import dev.leyline.mesh.Rejection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TraceKind { RECEIVED, SENT, RELAYED, CARRIED, DUPLICATE, ECHO, LOOP, RECEIPT, BLOCKED }

/** One routing decision this phone just made, for the packet log and the map animation. */
data class TraceEntry(
    val seq: Long,
    val at: Long,
    val kind: TraceKind,
    val packetId: MessageId,
    val sos: Boolean,
    /** The packet is a delivery receipt, not a message. */
    val receipt: Boolean,
    val origin: NodeId,
    /** The neighbour it came from or went to; for [TraceKind.RECEIPT], the node that confirmed. */
    val peer: NodeId,
    /** Node by node, the way the packet travelled: from its origin to us, or to the neighbour it left for. */
    val route: List<NodeId>,
    val text: String? = null,
    val hops: Int = 0,
    /** TTL the packet arrived or left with. */
    val ttl: Int = 0,
    /** CARRIED: how long we held it. RECEIPT: send-to-receipt round trip, if we know when we sent. */
    val millis: Long? = null,
    val rejection: Rejection? = null,
)

/**
 * Demo mode: turns mesh events into a readable log of what the router decided,
 * so dedup, TTL, relaying and store-and-forward can be watched as they happen.
 *
 * Only times measured on this phone's clock are shown. Phones in a blackout
 * can't sync clocks, so "sent at" from another phone can be seconds off; a
 * receipt's round trip starts and ends here and is exact.
 */
class PacketTrace(private val self: NodeId, private val capacity: Int = 60) {
    private val _entries = MutableStateFlow<List<TraceEntry>>(emptyList())

    /** Newest first. */
    val entries: StateFlow<List<TraceEntry>> = _entries.asStateFlow()

    private var seq = 0L
    private val sent = object : LinkedHashMap<MessageId, Packet>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MessageId, Packet>) = size > 200
    }

    /** Our own message, so its receipts can show a round trip. */
    @Synchronized
    fun onSent(packet: Packet) {
        sent[packet.id] = packet
    }

    @Synchronized
    fun onEvent(event: MeshEvent) {
        val entry = when (event) {
            is MeshEvent.Heard -> heard(event)
            is MeshEvent.Forwarded -> forwarded(event)
            is MeshEvent.Dropped -> {
                val p = event.packet
                val kind = when (event.reason) {
                    DropReason.DUPLICATE -> TraceKind.DUPLICATE
                    DropReason.OWN_PACKET -> TraceKind.ECHO
                    DropReason.LOOP -> TraceKind.LOOP
                }
                entry(event.receivedAt, kind, p, event.neighbour, listOf(event.neighbour, self))
            }
            is MeshEvent.Receipt -> TraceEntry(
                seq = 0,
                at = event.receivedAt,
                kind = TraceKind.RECEIPT,
                packetId = event.messageId,
                sos = sent[event.messageId]?.priority == Priority.SOS,
                receipt = true,
                origin = event.by,
                peer = event.by,
                route = event.route,
                hops = event.hops,
                millis = sent[event.messageId]?.let { event.receivedAt - it.createdAt },
            )
            is MeshEvent.Rejected -> entry(
                event.receivedAt,
                TraceKind.BLOCKED,
                event.packet,
                event.neighbour,
                listOf(event.neighbour, self),
                text = textOf(event.packet),
            ).copy(rejection = event.reason)
            // Heard already logs every new message, including the ones delivered to us.
            is MeshEvent.Delivered -> null
        } ?: return
        _entries.value = (listOf(entry.copy(seq = ++seq)) + _entries.value).take(capacity)
    }

    private fun heard(event: MeshEvent.Heard): TraceEntry? {
        val p = event.packet
        // Receipts passing through show up when we forward them; ours when they arrive.
        if (p.type != PacketType.MESSAGE) return null
        val forUs = p.destination == self || p.destination == NodeId.BROADCAST
        return entry(
            event.receivedAt,
            TraceKind.RECEIVED,
            p,
            event.neighbour,
            listOf(p.origin) + p.path + self,
            // Someone else's private message: we carry it, but it isn't ours to read.
            text = if (forUs) textOf(p) else null,
        ).copy(hops = p.relayCount + 1)
    }

    private fun forwarded(event: MeshEvent.Forwarded): TraceEntry {
        val p = event.packet
        val kind = when {
            event.heldMillis >= CARRIED_AFTER_MILLIS -> TraceKind.CARRIED
            p.origin == self -> TraceKind.SENT
            else -> TraceKind.RELAYED
        }
        return entry(event.sentAt, kind, p, event.to, listOf(p.origin) + p.path + event.to)
            .copy(millis = event.heldMillis)
    }

    private fun entry(at: Long, kind: TraceKind, p: Packet, peer: NodeId, route: List<NodeId>, text: String? = null) =
        TraceEntry(
            seq = 0,
            at = at,
            kind = kind,
            packetId = p.id,
            sos = p.priority == Priority.SOS,
            receipt = p.type == PacketType.ACK,
            origin = p.origin,
            peer = peer,
            route = route,
            text = text,
            ttl = p.ttl,
        )

    private fun textOf(p: Packet): String? {
        if (p.type != PacketType.MESSAGE) return null
        val body = runCatching { MessageBody.decode(p.payload) }.getOrNull() ?: return null
        val sealed = body.sealed
        return when {
            sealed != null -> "\uD83D\uDD12 encrypted, ${sealed.size} bytes"
            body.ring != null -> "ring request"
            else -> body.text.ifEmpty { null }
        }
    }

    private companion object {
        /** Relays wait at most a few hundred ms (jitter, a retry); anything longer was carried. */
        const val CARRIED_AFTER_MILLIS = 2_000L
    }
}
