package dev.leyline.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

data class MeshConfig(
    val defaultTtl: Int = Packet.DEFAULT_TTL,
    /** Relays wait a random 0..n ms before forwarding, so neighbours don't all transmit at once. */
    val forwardJitterMillis: Long = 150,
    val sosJitterMillis: Long = 20,
    val normalLifetimeMillis: Long = 30 * MINUTE,
    val sosLifetimeMillis: Long = 6 * 60 * MINUTE,
    /** Receipts matter only while the sender is waiting; carrying them for hours just spreads them. */
    val receiptLifetimeMillis: Long = 10 * MINUTE,
    /** An SOS receipt may have to ride back with a person walking between groups, so it lives longer. */
    val sosReceiptLifetimeMillis: Long = 60 * MINUTE,
    /** Immediate retries of a failed write (GATT 133 and friends) before waiting for the resync. */
    val writeRetries: Int = 2,
    val retryDelayMillis: Long = 250,
    /** How often stored packets are re-offered to current neighbours (retries failed writes). */
    val resyncIntervalMillis: Long = 15_000,
    val queueCapacity: Int = 256,
    /**
     * Memory cap only (~100 B per id, so 5 MB at 50 000). Eviction is meant to be
     * by time; the outbox backs it up for ids that still circulate.
     */
    val seenCapacity: Int = 50_000,
    /** Must outlive every packet lifetime, or an old packet could be accepted twice. */
    val seenExpiryMillis: Long = 7 * 60 * MINUTE,
    /** Ask receivers of SOS broadcasts and unicast messages to send a delivery receipt. */
    val receipts: Boolean = true,
    val relay: Boolean = true,
    /** Drop unsigned packets. On in the app; tests and the simulator may run unsigned. */
    val requireSignatures: Boolean = false,
) {
    init {
        require(seenExpiryMillis > maxOf(normalLifetimeMillis, sosLifetimeMillis)) {
            "seen cache must remember ids longer than packets live"
        }
    }

    private companion object {
        const val MINUTE = 60_000L
    }
}

sealed interface MeshEvent {
    /**
     * Any new packet that passed through us, relayed or delivered. Its origin and
     * path reveal links we can't see directly, which is what draws the mesh map.
     */
    data class Heard(val packet: Packet, val neighbour: NodeId, val receivedAt: Long) : MeshEvent

    /** A message addressed to us, or a broadcast. [hops] = radio links it crossed. */
    data class Delivered(val packet: Packet, val neighbour: NodeId, val hops: Int, val receivedAt: Long) :
        MeshEvent

    /**
     * Node [by] (called [name], if it said) confirmed it received our message [messageId],
     * [hops] links away. [route] is the way the receipt came back: [by], relays, us.
     */
    data class Receipt(
        val messageId: MessageId,
        val by: NodeId,
        val hops: Int,
        val receivedAt: Long,
        val name: String? = null,
        val route: List<NodeId> = emptyList(),
    ) : MeshEvent

    /**
     * [neighbour] handed us a packet that failed its signature check, so it was
     * neither shown nor relayed. [reason] says how: unsigned, claiming another
     * node's id, altered, or (IMPOSTOR) a valid key that isn't the one pinned for that id.
     */
    data class Rejected(val packet: Packet, val neighbour: NodeId, val reason: Rejection, val receivedAt: Long) :
        MeshEvent

    /** [neighbour] handed us a copy we ignored: one we already had, our own coming back, or a loop. */
    data class Dropped(val packet: Packet, val neighbour: NodeId, val reason: DropReason, val receivedAt: Long) :
        MeshEvent

    /**
     * [packet] went out over the radio to [to]. [heldMillis] is how long it sat in
     * our outbox first: about zero when relayed straight on, long when we carried it
     * until [to] came into range (store-and-forward).
     */
    data class Forwarded(val packet: Packet, val to: NodeId, val sentAt: Long, val heldMillis: Long) : MeshEvent
}

enum class Rejection { UNSIGNED, WRONG_ORIGIN, BAD_SIGNATURE, IMPOSTOR }

/** Counters for the mesh health screen and the stress-test reports. */
data class MeshStats(
    val originated: Int = 0,
    val delivered: Int = 0,
    val relayed: Int = 0,
    val sent: Int = 0,
    val sendFailures: Int = 0,
    val duplicates: Int = 0,
    val loops: Int = 0,
    val echoes: Int = 0,
    val malformed: Int = 0,
    val receipts: Int = 0,
    val queueDrops: Int = 0,
    /** Repeats forwarded again because they had more hops left than the first copy. */
    val upgrades: Int = 0,
    /** Packets dropped for a missing, wrong or impostor signature. */
    val rejected: Int = 0,
)

/**
 * One node of the mesh: joins the pure [Router] to a [Transport] and an [Outbox].
 *
 * Store-and-forward: every packet we originate or relay is written to the outbox
 * first, then offered to each neighbour that doesn't already have it. When a new
 * neighbour connects, everything still alive in the outbox is offered to it. So a
 * message crosses a gap in the network by being carried until a link appears.
 *
 * Concurrency: link events are handled one at a time by [start]'s coroutine. Each
 * neighbour has its own sender coroutine and [ForwardQueue], so a slow phone
 * never holds up the others. [lock] guards the router and the link table.
 */
class MeshNode(
    val self: NodeId,
    private val transport: Transport,
    private val outbox: Outbox,
    private val clock: () -> Long,
    private val config: MeshConfig = MeshConfig(),
    private val random: Random = Random.Default,
    /** Signs what we originate; null leaves our packets unsigned (tests, simulator). */
    private val signer: PacketSigner? = null,
    private val pins: KeyPins = InMemoryKeyPins(),
) {
    init {
        if (signer != null) {
            require(PacketSignatures.nodeIdFor(signer.publicKey) == self) { "node id must derive from the signing key" }
        }
    }

    private inner class Link(val id: NodeId) {
        val queue = ForwardQueue(config.queueCapacity)
        val wake = Channel<Unit>(Channel.CONFLATED)
        var inFlight: Packet? = null
        var job: Job? = null
    }

    private val lock = Mutex()
    private val router = Router(self, SeenCache(config.seenCapacity, config.seenExpiryMillis, clock), clock, random)
    private val links = HashMap<NodeId, Link>()
    private val reachable = HashSet<NodeId>()
    private var blocked: Set<NodeId> = emptySet()
    private lateinit var scope: CoroutineScope

    @Volatile
    var relayEnabled: Boolean = config.relay

    /** Put in our delivery receipts so senders see a name, not a node id. */
    @Volatile
    var displayName: String? = null

    private val _events = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<MeshEvent> = _events.asSharedFlow()

    private val _stats = MutableStateFlow(MeshStats())
    val stats: StateFlow<MeshStats> = _stats.asStateFlow()

    private val _neighbours = MutableStateFlow<Set<NodeId>>(emptySet())
    val neighbours: StateFlow<Set<NodeId>> = _neighbours.asStateFlow()

    /**
     * Runs the node until [scope] is cancelled. [knownIds] are messages handled
     * before a restart (from the app's database), so they aren't delivered twice.
     */
    fun start(scope: CoroutineScope, knownIds: Collection<MessageId> = emptyList()): Job = scope.launch {
        this@MeshNode.scope = this
        val stored = outbox.live(clock()).map { it.packet.id }
        lock.withLock { router.remember(knownIds + stored) }
        launch { maintain() }
        transport.events.collect { event ->
            when (event) {
                is LinkEvent.Connected -> onConnected(event.neighbour)
                is LinkEvent.Disconnected -> onDisconnected(event.neighbour)
                is LinkEvent.Received -> onReceived(event.neighbour, event.bytes)
            }
        }
    }

    /** Originates a message. It is stored before anything is sent, so it survives a crash. */
    suspend fun send(
        body: ByteArray,
        destination: NodeId = NodeId.BROADCAST,
        priority: Priority = Priority.NORMAL,
        ttl: Int = config.defaultTtl,
    ): Packet {
        val packet = signed(lock.withLock { router.originate(body, destination, priority, PacketType.MESSAGE, ttl) })
        _stats.update { it.copy(originated = it.originated + 1) }
        dispatchOwn(packet)
        return packet
    }

    /** Topology mode: act as if these neighbours were out of range, to force multi-hop in one room. */
    suspend fun setBlocked(nodes: Set<NodeId>) {
        lock.withLock {
            blocked = nodes
            links.keys.filter { it in nodes }.forEach { closeLink(it) }
            reachable.filter { it !in nodes }.forEach { openLink(it) }
        }
        resync()
    }

    private suspend fun onConnected(neighbour: NodeId) {
        lock.withLock {
            reachable += neighbour
            if (neighbour in blocked) return
            openLink(neighbour)
        }
        resync()
    }

    private suspend fun onDisconnected(neighbour: NodeId) = lock.withLock {
        reachable -= neighbour
        closeLink(neighbour)
    }

    private suspend fun onReceived(from: NodeId, bytes: ByteArray) {
        val packet = try {
            PacketCodec.decode(bytes)
        } catch (e: MalformedPacketException) {
            _stats.update { it.copy(malformed = it.malformed + 1) }
            return
        }
        // Before the router: a forgery must not even enter the seen cache, or it could
        // shadow the genuine packet with the same id.
        rejection(packet)?.let { reason ->
            _stats.update { it.copy(rejected = it.rejected + 1) }
            _events.emit(MeshEvent.Rejected(packet, from, reason, clock()))
            return
        }
        var decision = lock.withLock {
            if (from in blocked) return
            router.onReceive(packet)
        }
        // The seen cache is bounded; a packet still in our outbox was handled even if the cache forgot it.
        if (decision is RouteDecision.Accept && !decision.upgrade && outbox.get(packet.id) != null) {
            decision = RouteDecision.Drop(DropReason.DUPLICATE)
        }
        // Everyone on the packet's path, and the neighbour who just sent it, already has it.
        val holders = packet.path.toSet() + packet.origin + from
        when (decision) {
            is RouteDecision.Drop -> {
                _stats.update {
                    when (decision.reason) {
                        DropReason.DUPLICATE -> it.copy(duplicates = it.duplicates + 1)
                        DropReason.OWN_PACKET -> it.copy(echoes = it.echoes + 1)
                        DropReason.LOOP -> it.copy(loops = it.loops + 1)
                    }
                }
                _events.emit(MeshEvent.Dropped(packet, from, decision.reason, clock()))
                // A duplicate still teaches us who has the packet, so we never send it back to them.
                outbox.get(packet.id)?.let { stored -> outbox.addHolders(packet.id, holdersAsGood(packet, stored.packet.ttl)) }
            }
            is RouteDecision.Accept -> onAccepted(packet, from, decision, holders)
        }
    }

    private suspend fun rejection(packet: Packet): Rejection? {
        if (packet.auth == null && !config.requireSignatures) return null
        return when (PacketSignatures.verify(packet)) {
            Verdict.VALID -> if (pins.check(packet.origin, packet.auth!!.publicKey)) null else Rejection.IMPOSTOR
            Verdict.UNSIGNED -> Rejection.UNSIGNED
            Verdict.WRONG_ORIGIN -> Rejection.WRONG_ORIGIN
            Verdict.BAD_SIGNATURE -> Rejection.BAD_SIGNATURE
        }
    }

    private fun signed(packet: Packet): Packet = signer?.let { PacketSignatures.sign(packet, it) } ?: packet

    /**
     * Nodes on [copy]'s route whose own copy reaches at least as far as ours ([ourTtl]).
     * The neighbour that sent [copy] had one more TTL than it carries now, the relay
     * before that two more, and so on back to the origin. A node holding a weaker
     * copy is not a holder: our copy would still take the message further through it.
     */
    private fun holdersAsGood(copy: Packet, ourTtl: Int): Set<NodeId> {
        val route = listOf(copy.origin) + copy.path
        return route.filterIndexedTo(HashSet()) { i, _ -> copy.ttl + (route.size - i) >= ourTtl }
    }

    private suspend fun onAccepted(packet: Packet, from: NodeId, decision: RouteDecision.Accept, holders: Set<NodeId>) {
        val now = clock()
        if (decision.upgrade) {
            if (!relayEnabled) return
            val forward = decision.forward ?: return
            // An upgrade may only change TTL and path. Same id with other content is someone
            // reusing the id, and must not replace the copy we verified.
            val stored = outbox.get(packet.id)
            if (stored != null && !sameMessage(stored.packet, packet)) return
            outbox.upgrade(forward, holders + self, now, now + lifetime(forward))
            _stats.update { it.copy(upgrades = it.upgrades + 1) }
            scheduleRelease(forward)
            return
        }
        _events.emit(MeshEvent.Heard(packet, from, now))
        if (packet.type == PacketType.ACK) onAck(packet, decision.deliver, now)
        if (decision.deliver && packet.type == PacketType.MESSAGE) {
            _stats.update { it.copy(delivered = it.delivered + 1) }
            _events.emit(MeshEvent.Delivered(packet, from, packet.relayCount + 1, now))
            if (config.receipts && (packet.destination == self || packet.priority == Priority.SOS)) {
                sendReceipt(packet)
            }
        }
        val forward = decision.forward ?: return
        if (!relayEnabled) return
        outbox.store(forward, holders + self, now, now + lifetime(forward))
        _stats.update { it.copy(relayed = it.relayed + 1) }
        scheduleRelease(forward)
    }

    private fun sameMessage(a: Packet, b: Packet): Boolean =
        a.origin == b.origin && a.type == b.type && a.priority == b.priority && a.destination == b.destination &&
            a.createdAt == b.createdAt && a.payload.contentEquals(b.payload) && a.auth == b.auth

    private fun scheduleRelease(forward: Packet) {
        val maxJitter = if (forward.priority == Priority.SOS) config.sosJitterMillis else config.forwardJitterMillis
        scope.launch {
            // Duplicates that arrive during the wait add holders, which shrinks who we send to.
            delay(random.nextLong(maxJitter + 1))
            release(forward.id)
        }
    }

    private suspend fun onAck(packet: Packet, forMe: Boolean, now: Long) {
        val ack = try {
            AckBody.decode(packet.payload)
        } catch (e: MalformedPacketException) {
            _stats.update { it.copy(malformed = it.malformed + 1) }
            return
        }
        // A unicast message that reached its destination no longer needs carrying.
        val stored = outbox.get(ack.acked)
        if (stored != null && stored.packet.destination == packet.origin) {
            outbox.remove(ack.acked)
            lock.withLock { links.values.forEach { it.queue.remove(ack.acked) } }
        }
        if (forMe) {
            _stats.update { it.copy(receipts = it.receipts + 1) }
            _events.emit(MeshEvent.Receipt(ack.acked, packet.origin, ack.hops, now, ack.name, listOf(packet.origin) + packet.path + self))
        }
    }

    private suspend fun sendReceipt(message: Packet) {
        val hops = message.relayCount + 1
        val ack = signed(lock.withLock {
            router.originate(
                payload = AckBody(message.id, hops, message.path.reversed() + message.origin, displayName).encode(),
                destination = message.origin,
                priority = message.priority,
                type = PacketType.ACK,
                // One spare hop in case the way back has moved since.
                ttl = (hops + 1).coerceAtMost(Packet.MAX_TTL),
            )
        })
        dispatchOwn(ack)
    }

    private suspend fun dispatchOwn(packet: Packet) {
        val now = clock()
        outbox.store(packet, setOf(self), now, now + lifetime(packet))
        release(packet.id)
    }

    /** Queues one stored packet for every current neighbour that should get it. */
    private suspend fun release(id: MessageId) {
        val stored = outbox.get(id) ?: return // delivered or expired meanwhile
        lock.withLock { targets(stored).forEach { enqueue(it, stored.packet) } }
    }

    /** Re-offers everything still alive in the outbox; queues ignore what they already hold. */
    private suspend fun resync() {
        val live = outbox.live(clock())
        if (live.isEmpty()) return
        lock.withLock { live.forEach { stored -> targets(stored).forEach { enqueue(it, stored.packet) } } }
    }

    private suspend fun maintain() {
        while (true) {
            delay(config.resyncIntervalMillis)
            outbox.purgeExpired(clock())
            resync()
        }
    }

    // Caller holds lock. A packet with a known next hop that we're linked to goes only there;
    // anything else floods to every neighbour that doesn't have it yet.
    private fun targets(stored: StoredPacket): List<Link> {
        nextHop(stored.packet)?.let { hop ->
            return if (hop in stored.holders) emptyList() else listOf(links.getValue(hop))
        }
        return links.values.filter { it.id !in stored.holders }
    }

    // Caller holds lock. Unicast to a neighbour: straight to it. A receipt: the linked node
    // nearest the origin along its route back, skipping ahead when the mesh allows it.
    private fun nextHop(packet: Packet): NodeId? {
        if (packet.destination == NodeId.BROADCAST) return null
        if (packet.destination in links) return packet.destination
        if (packet.type != PacketType.ACK) return null
        val route = try {
            AckBody.decode(packet.payload).route
        } catch (e: MalformedPacketException) {
            return null
        }
        return route.drop(route.indexOf(self) + 1).lastOrNull { it in links }
    }

    // Caller holds lock.
    private fun enqueue(link: Link, packet: Packet) {
        val inFlight = link.inFlight
        if (inFlight != null && inFlight.id == packet.id && inFlight.ttl >= packet.ttl) return
        if (link.queue.offer(packet)) {
            link.wake.trySend(Unit)
        } else if (packet.id !in link.queue) {
            _stats.update { it.copy(queueDrops = it.queueDrops + 1) }
        }
    }

    private suspend fun drain(link: Link) {
        for (signal in link.wake) {
            while (true) {
                val packet = lock.withLock { link.queue.poll()?.also { link.inFlight = it } } ?: break
                val bytes = PacketCodec.encode(packet)
                var ok = false
                for (attempt in 0..config.writeRetries) {
                    if (attempt > 0) {
                        delay(config.retryDelayMillis * attempt)
                        if (lock.withLock { links[link.id] !== link }) break
                    }
                    ok = transport.send(link.id, bytes)
                    if (ok) break
                    _stats.update { it.copy(sendFailures = it.sendFailures + 1) }
                }
                if (ok) {
                    val stored = outbox.get(packet.id)
                    // If a better copy replaced ours while this one was on air, the neighbour still needs it.
                    if ((stored?.packet?.ttl ?: 0) <= packet.ttl) outbox.addHolders(packet.id, setOf(link.id))
                    _stats.update { it.copy(sent = it.sent + 1) }
                    val now = clock()
                    _events.emit(MeshEvent.Forwarded(packet, link.id, now, stored?.let { now - it.storedAt } ?: 0))
                }
                lock.withLock { link.inFlight = null }
            }
        }
    }

    // Caller holds lock.
    private fun openLink(neighbour: NodeId) {
        if (neighbour in links) return
        val link = Link(neighbour)
        links[neighbour] = link
        link.job = scope.launch { drain(link) }
        _neighbours.value = links.keys.toSet()
    }

    // Caller holds lock. Queued packets stay in the outbox and are replayed on reconnect.
    private fun closeLink(neighbour: NodeId) {
        val link = links.remove(neighbour) ?: return
        link.job?.cancel()
        link.wake.close()
        _neighbours.value = links.keys.toSet()
    }

    private fun lifetime(packet: Packet): Long = when {
        packet.type == PacketType.ACK && packet.priority == Priority.SOS -> config.sosReceiptLifetimeMillis
        packet.type == PacketType.ACK -> config.receiptLifetimeMillis
        packet.priority == Priority.SOS -> config.sosLifetimeMillis
        else -> config.normalLifetimeMillis
    }
}
