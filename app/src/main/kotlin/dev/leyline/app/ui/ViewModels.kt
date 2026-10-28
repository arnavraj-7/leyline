package dev.leyline.app.ui

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.leyline.app.AppContainer
import dev.leyline.app.data.MessageEntity
import dev.leyline.app.data.MessageWithReceipts
import dev.leyline.app.mesh.BatteryState
import dev.leyline.app.mesh.BlockedPacket
import dev.leyline.app.mesh.SendResult
import dev.leyline.app.mesh.Topology
import dev.leyline.app.mesh.TraceEntry
import dev.leyline.ble.RadioState
import dev.leyline.ble.Sighting
import dev.leyline.mesh.Location
import dev.leyline.mesh.MediaKind
import dev.leyline.mesh.MeshStats
import dev.leyline.mesh.MessageBody
import dev.leyline.mesh.MessageId
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.ResponseStatus
import dev.leyline.mesh.SosDetails
import dev.leyline.mesh.SosKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

private val WHILE_VISIBLE = SharingStarted.WhileSubscribed(5_000)

data class MessagesUiState(
    val self: NodeId,
    /** The conversation: everything except rescuers' answers, which show on their SOS instead. */
    val messages: List<MessageWithReceipts> = emptyList(),
    /** Rescuers' answers per SOS id, oldest first. */
    val responses: Map<Long, List<MessageEntity>> = emptyMap(),
    /** A rescuer has set this phone ringing to find it. */
    val ringing: Boolean = false,
    val names: Map<NodeId, String> = emptyMap(),
    val linked: Int = 0,
    val nearby: Int = 0,
    val relaying: Boolean = true,
    val carrying: Int = 0,
    val running: Boolean = false,
    /** Message id to how many other phones have it, for "handed to 2 phones". */
    val handedTo: Map<Long, Int> = emptyMap(),
    /** Our own last GPS fix, to show distance and direction to an SOS. */
    val myLocation: Location? = null,
)

/** Optional triage picked before holding the SOS button. Nothing picked sends a plain SOS. */
data class SosDraft(val kind: SosKind? = null, val people: Int = 0, val injured: Boolean = false) {
    fun toDetails(): SosDetails? =
        if (kind == null && people == 0 && !injured) null else SosDetails(kind ?: SosKind.GENERAL, people, injured)
}

/**
 * Chat screen state. MVVM with one-way data flow: the screen renders [state]
 * and calls functions; it never mutates anything itself.
 */
class MessagesViewModel(private val container: AppContainer) : ViewModel() {
    private val mesh = container.mesh

    private val _replyTo = MutableStateFlow<NodeId?>(null)
    val replyTo: StateFlow<NodeId?> = _replyTo.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _sosDraft = MutableStateFlow(SosDraft())
    val sosDraft: StateFlow<SosDraft> = _sosDraft.asStateFlow()

    /** Live Bluetooth sightings, for the finder's signal meter. Updated about once a second. */
    val sightings = mesh.sightings

    private val linkState = combine(mesh.neighbours, mesh.sightings, mesh.relaying, mesh.running, mesh.ringing) { n, s, r, running, ringing ->
        Triple(n.size, s.size, r) to (running to ringing)
    }

    private val myLocation = flow {
        while (true) {
            emit(mesh.lastKnownLocation())
            delay(30_000)
        }
    }

    private val delivery = combine(container.repository.outboxSize, container.repository.handedTo(mesh.self), myLocation) {
            carrying, handed, location ->
        Triple(carrying, handed, location)
    }

    val state: StateFlow<MessagesUiState> = combine(
        container.repository.recent,
        mesh.topology,
        linkState,
        delivery,
    ) { messages, topology, (links, flags), (carrying, handed, location) ->
        val (responses, conversation) = messages.partition { it.message.respondsTo != null }
        MessagesUiState(
            self = mesh.self,
            messages = conversation,
            responses = responses.map { it.message }.groupBy { it.respondsTo!! }.mapValues { (_, list) -> list.sortedBy { it.receivedAt } },
            ringing = flags.second,
            names = namesFrom(messages, topology),
            linked = links.first,
            nearby = links.second,
            relaying = links.third,
            carrying = carrying,
            running = flags.first,
            handedTo = handed,
            myLocation = location,
        )
    }.stateIn(viewModelScope, WHILE_VISIBLE, MessagesUiState(mesh.self))

    fun setSosKind(kind: SosKind?) = _sosDraft.update { it.copy(kind = if (it.kind == kind) null else kind) }

    fun setSosPeople(people: Int) = _sosDraft.update { it.copy(people = people.coerceIn(0, 99)) }

    fun toggleInjured() = _sosDraft.update { it.copy(injured = !it.injured) }

    fun respond(sos: Long, status: ResponseStatus) {
        viewModelScope.launch {
            _notice.value = when (mesh.respond(MessageId(sos), status)) {
                is SendResult.Sent -> if (status == ResponseStatus.ON_THE_WAY) "Told them you're coming" else "Marked as found"
                SendResult.NotRunning -> "Mesh is off. Turn it on in Settings."
                SendResult.TooLong -> null
            }
        }
    }

    fun ring(victim: NodeId, sos: Long) {
        viewModelScope.launch {
            _notice.value = when (mesh.ring(victim, MessageId(sos))) {
                is SendResult.Sent -> "Asked their phone to ring for 30 s"
                else -> "Mesh is off. Turn it on in Settings."
            }
        }
    }

    fun stopRinging() = mesh.stopRinging()

    fun replyPrivately(node: NodeId?) {
        _replyTo.value = node
    }

    fun dismissNotice() {
        _notice.value = null
    }

    /** Calls [onDone] with true once sent, which tells the screen to clear the text field. */
    fun send(text: String, sos: Boolean, onDone: (Boolean) -> Unit) {
        val body = text.ifBlank { if (sos) DEFAULT_SOS else return onDone(false) }
        if (sos && body.length > MessageBody.SOS_MAX_CHARS) {
            _notice.value = "An SOS can be at most ${MessageBody.SOS_MAX_CHARS} characters"
            return onDone(false)
        }
        // An SOS always goes to everyone; a private reply is for chat only.
        val destination = if (sos) NodeId.BROADCAST else _replyTo.value ?: NodeId.BROADCAST
        val details = if (sos) _sosDraft.value.toDetails() else null
        viewModelScope.launch {
            val result = mesh.send(body, sos, destination, details)
            if (sos && result is SendResult.Sent) _sosDraft.value = SosDraft()
            _notice.value = when (result) {
                is SendResult.Sent -> when {
                    sos && result.withLocation -> "SOS sent with your location"
                    sos -> "SOS sent without location (GPS off or no fix yet)"
                    else -> null
                }
                SendResult.NotRunning -> "Mesh is off. Turn it on in Settings."
                SendResult.TooLong -> "Message too long for one packet"
            }
            onDone(result is SendResult.Sent)
        }
    }

    /** A photo or voice clip, to the person picked in "To:" or everyone. */
    fun sendMedia(kind: MediaKind, bytes: ByteArray) {
        val destination = _replyTo.value ?: NodeId.BROADCAST
        viewModelScope.launch {
            _notice.value = when (mesh.sendMedia(kind, bytes, destination)) {
                is SendResult.Sent -> null
                SendResult.NotRunning -> "Mesh is off. Turn it on in Settings."
                SendResult.TooLong -> "Too big to send over the mesh"
            }
        }
    }

    fun mediaFile(name: String): File = mesh.mediaFile(name)

    companion object {
        const val DEFAULT_SOS = "SOS: I need help."
    }
}

data class MeshUiState(
    val topology: Topology,
    val sightings: Map<NodeId, Sighting> = emptyMap(),
    val stats: MeshStats = MeshStats(),
    val radio: RadioState = RadioState(),
    val battery: BatteryState = BatteryState(100, false),
    val relaying: Boolean = true,
    val carryingSos: Boolean = false,
    val blocked: List<BlockedPacket> = emptyList(),
    /** Newest first: what the router did with each packet. */
    val trace: List<TraceEntry> = emptyList(),
    val results: Results = Results(),
)

private data class Power(val battery: BatteryState, val relaying: Boolean, val carryingSos: Boolean, val blocked: List<BlockedPacket>)

private data class Traffic(val stats: MeshStats, val trace: List<TraceEntry>, val results: Results)

/** Numbers for the demo, each measured on this phone's own clock. */
data class Results(
    /** Our messages that ask for a receipt (SOS and private), and how many got at least one. */
    val asked: Int = 0,
    val confirmed: Int = 0,
    /** Radio links crossed by messages delivered to us. */
    val averageHops: Double? = null,
    /** Send to receipt; both ends are our clock, so this is exact. */
    val medianRoundTripMillis: Long? = null,
    /** Battery points per hour since we last came off the charger, once there's 6 minutes of data. */
    val drainPerHour: Double? = null,
)

/** Mesh health dashboard and live map. */
class MeshViewModel(container: AppContainer) : ViewModel() {
    private val mesh = container.mesh

    private val power = combine(mesh.batteryState, mesh.relaying, mesh.carryingSos, mesh.blocked, ::Power)

    private var batteryStart: Pair<Int, Long>? = null

    private val results = combine(container.repository.recent, mesh.batteryState) { messages, battery ->
        val now = SystemClock.elapsedRealtime()
        if (battery.charging) batteryStart = null else if (batteryStart == null) batteryStart = battery.percent to now
        val asking = messages.filter { it.message.outgoing && (it.message.sos || it.message.destination != NodeId.BROADCAST.value) }
        val roundTrips = asking.flatMap { m -> m.receipts.map { it.receivedAt - m.message.createdAt } }.filter { it >= 0 }.sorted()
        val hops = messages.filter { !it.message.outgoing }.map { it.message.hops }
        Results(
            asked = asking.size,
            confirmed = asking.count { it.receipts.isNotEmpty() },
            averageHops = hops.takeIf { it.isNotEmpty() }?.average(),
            medianRoundTripMillis = roundTrips.getOrNull(roundTrips.size / 2),
            drainPerHour = batteryStart?.let { (percent, since) ->
                val hours = (now - since) / 3_600_000.0
                if (hours < 0.1) null else (percent - battery.percent) / hours
            },
        )
    }

    private val traffic = combine(mesh.stats, mesh.packetLog, results, ::Traffic)

    suspend fun freshLocation(): Location? = mesh.currentLocation()

    val state: StateFlow<MeshUiState> = combine(
        mesh.topology,
        mesh.sightings,
        traffic,
        mesh.radio,
        power,
    ) { topology, sightings, t, radio, p ->
        MeshUiState(topology, sightings, t.stats, radio, p.battery, p.relaying, p.carryingSos, p.blocked, t.trace, t.results)
    }.stateIn(viewModelScope, WHILE_VISIBLE, MeshUiState(Topology(mesh.self)))
}

data class KnownNode(val id: NodeId, val name: String?, val blocked: Boolean, val linked: Boolean)

/** Settings, including topology mode (blocking chosen nodes to force multi-hop). */
class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings = container.settings
    val mesh = container.mesh

    val knownNodes: StateFlow<List<KnownNode>> = combine(
        mesh.sightings,
        mesh.topology,
        mesh.neighbours,
        settings.blocked,
    ) { sightings, topology, linked, blocked ->
        (sightings.keys + topology.hops.keys + blocked)
            .filter { it != mesh.self }
            .map { KnownNode(it, topology.names[it], it in blocked, it in linked) }
            .sortedBy { it.name ?: it.id.toString() }
    }.stateIn(viewModelScope, WHILE_VISIBLE, emptyList())

    fun clearHistory() {
        viewModelScope.launch { container.repository.clearHistory() }
    }

    private val _forgeryResult = MutableStateFlow<String?>(null)
    val forgeryResult: StateFlow<String?> = _forgeryResult.asStateFlow()

    /** Security demo: impersonate [victim] and check the other phones refuse it. */
    fun sendForgedSos(victim: KnownNode) {
        viewModelScope.launch {
            val handed = mesh.sendForgedSos(victim.id)
            val who = victim.name ?: victim.id.toString()
            _forgeryResult.value = if (handed == 0) {
                "No linked phone to hand it to."
            } else {
                "Fake SOS claiming to be $who handed to $handed ${if (handed == 1) "phone" else "phones"}. Their Mesh tab should list it as blocked, and it must not appear in their messages."
            }
        }
    }
}

/** Latest name each node used, from its messages and from what the mesh has learned. */
private fun namesFrom(messages: List<MessageWithReceipts>, topology: Topology): Map<NodeId, String> {
    val names = HashMap(topology.names)
    // Messages are newest first; walking oldest to newest keeps each node's latest name.
    for (m in messages.asReversed()) {
        m.message.senderName?.let { names[NodeId(m.message.origin)] = it }
        m.receipts.forEach { r -> r.name?.let { names[NodeId(r.node)] = it } }
    }
    return names
}
