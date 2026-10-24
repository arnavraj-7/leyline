package dev.leyline.app.mesh

import android.content.Context
import dev.leyline.app.data.LeylineDatabase
import dev.leyline.app.data.MessageRepository
import dev.leyline.app.data.RoomKeyPins
import dev.leyline.app.data.RoomOutbox
import dev.leyline.app.data.Settings
import dev.leyline.ble.BleTransport
import dev.leyline.ble.RadioState
import dev.leyline.ble.Sighting
import dev.leyline.mesh.MeshConfig
import dev.leyline.mesh.MeshEvent
import dev.leyline.mesh.MeshNode
import dev.leyline.mesh.MeshStats
import dev.leyline.mesh.Location
import dev.leyline.mesh.MessageBody
import dev.leyline.mesh.MessageId
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.P256
import dev.leyline.mesh.Packet
import dev.leyline.mesh.PacketCodec
import dev.leyline.mesh.PacketSignatures
import dev.leyline.mesh.PacketType
import dev.leyline.mesh.Priority
import dev.leyline.mesh.Rejection
import dev.leyline.mesh.Response
import dev.leyline.mesh.ResponseStatus
import dev.leyline.mesh.Sealing
import dev.leyline.mesh.SosDetails
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface SendResult {
    data class Sent(val id: MessageId, val withLocation: Boolean, val encrypted: Boolean = false) : SendResult
    data object NotRunning : SendResult
    data object TooLong : SendResult
}

/** A packet we refused because its signature didn't hold up, for the security log on the Mesh tab. */
data class BlockedPacket(
    val claimedOrigin: NodeId,
    val from: NodeId,
    val reason: Rejection,
    val sos: Boolean,
    val text: String?,
    val at: Long,
)

/**
 * Owns the running mesh for the whole app process: one [BleTransport], one
 * [MeshNode], and the glue to the database, settings and notifications. The
 * foreground service keeps the process alive; the UI just observes these flows.
 *
 * Manual dependency injection: [dev.leyline.app.AppContainer] builds this once
 * and hands it to whoever needs it, which keeps object lifetimes explicit.
 */
class MeshController(
    private val context: Context,
    private val settings: Settings,
    private val signer: KeystoreSigner,
    private val boxKeys: BoxKeys,
    private val repository: MessageRepository,
    database: LeylineDatabase,
    private val notifier: Notifier,
    private val appScope: CoroutineScope,
) {
    private class Session(val transport: BleTransport, val node: MeshNode, val scope: CoroutineScope)

    val self: NodeId = signer.nodeId
    private val outboxDao = database.outbox()
    private val outbox = RoomOutbox(outboxDao)
    private val pins = RoomKeyPins(database.keyPins())
    private val battery = BatteryMonitor(context)
    private val locator = LocationProvider(context)
    private val topologyTracker = TopologyTracker(self)
    private val trace = PacketTrace(self)
    private val alarm = Alarm(context, appScope)
    private var session: Session? = null
    private var uiVisible = false

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _neighbours = MutableStateFlow<Set<NodeId>>(emptySet())
    val neighbours: StateFlow<Set<NodeId>> = _neighbours.asStateFlow()

    private val _stats = MutableStateFlow(MeshStats())
    val stats: StateFlow<MeshStats> = _stats.asStateFlow()

    private val _radio = MutableStateFlow(RadioState())
    val radio: StateFlow<RadioState> = _radio.asStateFlow()

    private val _sightings = MutableStateFlow<Map<NodeId, Sighting>>(emptyMap())
    val sightings: StateFlow<Map<NodeId, Sighting>> = _sightings.asStateFlow()

    private val _topology = MutableStateFlow(Topology(self))
    val topology: StateFlow<Topology> = _topology.asStateFlow()

    private val _relaying = MutableStateFlow(true)

    /** Relay actually on: the user's switch, unless battery saver has taken over. */
    val relaying: StateFlow<Boolean> = _relaying.asStateFlow()

    val batteryState = battery.state

    private val _blocked = MutableStateFlow<List<BlockedPacket>>(emptyList())

    /** Most recent first, capped: forgeries this phone refused to show or relay. */
    val blocked: StateFlow<List<BlockedPacket>> = _blocked.asStateFlow()

    /** Demo mode: the router's decisions, newest first. */
    val packetLog: StateFlow<List<TraceEntry>> = trace.entries

    /** A rescuer asked this phone to ring so they can find it. */
    val ringing: StateFlow<Boolean> = alarm.ringing

    private val _carryingSos = MutableStateFlow(false)
    val carryingSos: StateFlow<Boolean> = _carryingSos.asStateFlow()

    /** Starts the radios and the node. Idempotent; caller has checked permissions. */
    @Synchronized
    fun start() {
        if (session != null) return
        val scope = CoroutineScope(appScope.coroutineContext + SupervisorJob(appScope.coroutineContext[Job]))
        val transport = BleTransport(context, self, scope, name = { settings.name.value })
        val node = MeshNode(
            self,
            transport,
            outbox,
            clock = System::currentTimeMillis,
            config = MeshConfig(requireSignatures = true),
            signer = signer,
            pins = pins,
        )
        session = Session(transport, node, scope)
        _running.value = true
        battery.start()

        scope.launch {
            // Subscribe before anything can be delivered: SharedFlow doesn't replay.
            launch { node.events.collect(::onEvent) }
            launch { node.stats.collect { _stats.value = it } }
            launch {
                node.neighbours.collect {
                    _neighbours.value = it
                    topologyTracker.onNeighbours(it, System.currentTimeMillis())
                    publishTopology()
                }
            }
            launch { transport.radio.collect { _radio.value = it } }
            launch { transport.sightings.collect { _sightings.value = it } }
            launch { settings.blocked.collect { node.setBlocked(it) } }
            launch {
                settings.name.collect {
                    node.displayName = it
                    topologyTracker.onName(self, it)
                }
            }
            launch {
                transport.names.collect { names ->
                    names.forEach { (id, name) -> topologyTracker.onName(id, name) }
                    publishTopology()
                }
            }
            launch {
                combine(settings.relay, settings.batterySaver, battery.state) { relay, saver, battery ->
                    relay && !(saver && battery.low)
                }.collect {
                    node.relayEnabled = it
                    _relaying.value = it
                    refreshAdvert()
                }
            }
            launch {
                while (true) {
                    refreshAdvert()
                    publishTopology()
                    delay(10_000)
                }
            }
            node.start(this, repository.knownIds())
            transport.start()
        }
    }

    @Synchronized
    fun stop() {
        val current = session ?: return
        session = null
        current.transport.stop()
        current.scope.cancel()
        battery.stop()
        _running.value = false
        _neighbours.value = emptySet()
        _radio.value = RadioState()
    }

    /** The UI is on screen: scan at low latency so phones find each other in about a second. */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        refreshScanMode()
    }

    suspend fun send(
        text: String,
        sos: Boolean,
        destination: NodeId = NodeId.BROADCAST,
        details: SosDetails? = null,
    ): SendResult {
        val location = if (sos && settings.shareLocation.value) locator.current() else null
        return send(MessageBody(text.trim(), location, settings.name.value, details = details.takeIf { sos }), sos, destination)
    }

    /**
     * Answers an SOS: "I'm coming", or "Found them" once there. Sent to everyone,
     * so the person sees help is on the way and other rescuers see who's covering
     * whom instead of all running to the same SOS. Carries our location if
     * sharing is on, so the victim sees how far away we are.
     */
    suspend fun respond(sos: MessageId, status: ResponseStatus): SendResult {
        val location = if (settings.shareLocation.value) locator.current() else null
        val text = when (status) {
            ResponseStatus.ON_THE_WAY -> "On my way"
            ResponseStatus.FOUND -> "Found them"
        }
        return send(MessageBody(text, location, settings.name.value, response = Response(sos, status)), sos = false, NodeId.BROADCAST)
    }

    /** Asks [victim] to ring so we can find it; it only obeys for its own live SOS [sos]. */
    suspend fun ring(victim: NodeId, sos: MessageId): SendResult {
        val node = session?.node ?: return SendResult.NotRunning
        val packet = node.send(MessageBody("", senderName = settings.name.value, ring = sos).encode(), destination = victim, ttl = settings.ttl.value)
        trace.onSent(packet)
        return SendResult.Sent(packet.id, withLocation = false)
    }

    /** Stops the alarm and its notification together, from the banner or the notification. */
    fun stopRinging() {
        alarm.stop()
        notifier.cancelRinging()
    }

    private suspend fun send(body: MessageBody, sos: Boolean, destination: NodeId): SendResult {
        val node = session?.node ?: return SendResult.NotRunning
        val (wire, encrypted) = forTheAir(body, sos, destination)
        val encoded = wire.encode()
        if (encoded.size > Packet.MAX_PAYLOAD) return SendResult.TooLong
        val packet = node.send(
            body = encoded,
            destination = destination,
            priority = if (sos) Priority.SOS else Priority.NORMAL,
            ttl = settings.ttl.value,
        )
        trace.onSent(packet)
        repository.recordOutgoing(packet, body, System.currentTimeMillis(), encrypted)
        refreshAdvert()
        return SendResult.Sent(packet.id, body.location != null, encrypted)
    }

    /**
     * What actually goes on air. Chat carries our encryption key, so whoever we
     * talk to can write back privately; an SOS doesn't, to stay one radio frame.
     * A private message is sealed for its recipient if we know their key, which
     * we learn from any message they sent. Otherwise it goes signed but readable.
     */
    private suspend fun forTheAir(body: MessageBody, sos: Boolean, destination: NodeId): Pair<MessageBody, Boolean> {
        if (sos) return body to false
        if (destination == NodeId.BROADCAST) return body.copy(boxKey = boxKeys.publicKey) to false
        val theirs = repository.boxKey(destination) ?: return body.copy(boxKey = boxKeys.publicKey) to false
        val inner = MessageBody(body.text, body.location)
        // A key that isn't a point on the curve makes seal throw; send it readable rather
        // than crash. It is marked unencrypted, so the UI doesn't claim otherwise.
        val sealed = runCatching { Sealing.seal(inner.encode(), boxKeys.private, theirs, self, destination) }.getOrNull()
            ?: return body.copy(boxKey = boxKeys.publicKey) to false
        return MessageBody("", senderName = body.senderName, boxKey = boxKeys.publicKey, sealed = sealed) to true
    }

    /** The readable body of a delivered message, and whether it was sealed. */
    private suspend fun opened(body: MessageBody, packet: Packet): Pair<MessageBody, Boolean> {
        val sealed = body.sealed ?: return body to false
        val theirs = repository.boxKey(packet.origin)
        val plain = theirs?.let { Sealing.open(sealed, boxKeys.private, it, packet.origin, packet.destination) }
            ?: return body.copy(sealed = null, text = "Encrypted message that couldn't be opened on this phone") to false
        val inner = runCatching { MessageBody.decode(plain) }.getOrNull() ?: return body.copy(sealed = null) to false
        return body.copy(text = inner.text, location = inner.location, sealed = null) to true
    }

    /**
     * Security demo: sends linked phones an SOS that claims to come from [victim],
     * signed with our own key because we don't have theirs. Every receiver should
     * refuse it and list it under "Blocked" on its Mesh tab. Returns how many
     * phones it was handed to.
     */
    suspend fun sendForgedSos(victim: NodeId): Int {
        val current = session ?: return 0
        val packet = Packet(
            id = MessageId(kotlin.random.Random.nextLong()),
            type = PacketType.MESSAGE,
            priority = Priority.SOS,
            origin = victim,
            destination = NodeId.BROADCAST,
            ttl = settings.ttl.value,
            createdAt = System.currentTimeMillis(),
            path = emptyList(),
            payload = MessageBody("SOS: fire at the north gate, everyone come now", senderName = "Rescue Team").encode(),
        )
        val bytes = PacketCodec.encode(PacketSignatures.sign(packet, signer))
        return current.node.neighbours.value.count { current.transport.send(it, bytes) }
    }

    fun locationPermitted(): Boolean = locator.permitted()

    private fun isCurvePoint(key: ByteArray): Boolean = runCatching { P256.decompress(key) }.isSuccess

    fun lastKnownLocation(): Location? = locator.lastKnown()

    private suspend fun onEvent(event: MeshEvent) {
        trace.onEvent(event)
        when (event) {
            is MeshEvent.Heard -> {
                topologyTracker.onHeard(event.packet, event.receivedAt)
                // Right away, so the map can draw the packet's route while it animates.
                publishTopology()
                if (event.packet.type == PacketType.MESSAGE && event.packet.priority == Priority.SOS) refreshAdvert()
            }
            is MeshEvent.Delivered -> {
                val received = runCatching { MessageBody.decode(event.packet.payload) }.getOrNull() ?: return
                // Signed by the origin, so this key really is theirs. Signed isn't the same as
                // well formed, though: keep only a key that decodes to a point on the curve.
                received.boxKey?.takeIf(::isCurvePoint)?.let { repository.rememberBoxKey(event.packet.origin, it, event.receivedAt) }
                val sender = received.senderName ?: event.packet.origin.toString()
                received.senderName?.let { topologyTracker.onName(event.packet.origin, it) }
                // A ring request is a command, not something to show; the router already deduplicated it.
                received.ring?.let { sos ->
                    // Only for our own live SOS: otherwise anyone could set off any phone on the mesh.
                    val since = System.currentTimeMillis() - SOS_LIFETIME_MILLIS
                    if (event.packet.destination == self && repository.isOwnSos(sos.value, since)) {
                        alarm.start()
                        notifier.ringing(sender)
                    }
                    return
                }
                val (body, encrypted) = opened(received, event.packet)
                if (!repository.recordDelivered(event, body, encrypted)) return
                body.response?.let { response ->
                    if (repository.isOwnSos(response.sos.value, since = 0)) notifier.helpComing(event.packet.id.value, sender, response.status)
                }
                if (event.packet.priority == Priority.SOS) {
                    notifier.incomingSos(
                        messageId = event.packet.id.value,
                        sender = body.senderName ?: event.packet.origin.toString(),
                        text = body.text,
                        hops = event.hops,
                    )
                }
                publishTopology()
            }
            is MeshEvent.Receipt -> {
                repository.recordReceipt(event)
                event.name?.let { topologyTracker.onName(event.by, it) }
            }
            is MeshEvent.Rejected -> {
                val packet = event.packet
                val body = if (packet.type == PacketType.MESSAGE) runCatching { MessageBody.decode(packet.payload) }.getOrNull() else null
                val entry = BlockedPacket(
                    claimedOrigin = packet.origin,
                    from = event.neighbour,
                    reason = event.reason,
                    sos = packet.priority == Priority.SOS,
                    text = body?.text,
                    at = event.receivedAt,
                )
                _blocked.value = (listOf(entry) + _blocked.value).take(20)
            }
            is MeshEvent.Dropped, is MeshEvent.Forwarded -> Unit
        }
    }

    // SOS beacon mode: while we carry a live SOS, advertise it and scan hard so
    // any phone that comes into range gets it as fast as possible.
    private suspend fun refreshAdvert() {
        val transport = session?.transport ?: return
        val sos = outboxDao.carryingSos(System.currentTimeMillis())
        _carryingSos.value = sos
        transport.setAdvert(carryingSos = sos, relaying = _relaying.value)
        refreshScanMode()
    }

    private fun refreshScanMode() {
        session?.transport?.setAggressive(uiVisible || _carryingSos.value)
    }

    private fun publishTopology() {
        _topology.value = topologyTracker.snapshot(System.currentTimeMillis())
    }

    private companion object {
        val SOS_LIFETIME_MILLIS = MeshConfig().sosLifetimeMillis
    }
}
