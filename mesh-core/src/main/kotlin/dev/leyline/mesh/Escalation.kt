package dev.leyline.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * A fast side channel to one neighbour at a time: Wi-Fi Direct on phones. It finds
 * no one by itself. [EscalatingTransport] hands it the other side's credentials
 * over the Bluetooth link that is already up.
 */
interface BulkLink {
    val events: Flow<BulkEvent>

    /** Could make a group right now: radio on, permission held, not already in a group. */
    fun canHost(): Boolean

    /** Could join a group from its name and passphrase alone (Android 10+). */
    fun canJoin(): Boolean

    /** Makes a group only [peer] is meant to join. Returns what [peer] needs to join, or null. */
    suspend fun host(peer: NodeId): BulkOffer?

    /** Joins [peer]'s group. True once the link is up ([BulkEvent.Up] follows). */
    suspend fun join(peer: NodeId, offer: BulkOffer): Boolean

    suspend fun send(peer: NodeId, bytes: ByteArray): Boolean

    /** Tears the link or the half-made group down. Safe to call when there is none. */
    fun close(peer: NodeId)
}

sealed interface BulkEvent {
    val peer: NodeId

    data class Up(override val peer: NodeId) : BulkEvent

    data class Down(override val peer: NodeId) : BulkEvent

    class Received(override val peer: NodeId, val bytes: ByteArray) : BulkEvent
}

/** How to join a group: its network name and passphrase, and the port its owner listens on. */
data class BulkOffer(val network: String, val passphrase: String, val port: Int)

data class EscalationConfig(
    /**
     * Below this BLE finishes before a Wi-Fi Direct group is even up (3-10 s). At
     * 2-10 KB/s over BLE, 24 KB is where the switch starts to pay off.
     */
    val thresholdBytes: Int = 24 * 1024,
    /** Ask, group, join and socket must all be done by then, or we stay on BLE. */
    val setupTimeoutMillis: Long = 30_000,
    /** A bulk link this long without traffic is closed; Wi-Fi costs more battery than BLE. */
    val idleMillis: Long = 20_000,
    /** After a refusal, a failure or a close, don't ask the same neighbour again for this long. */
    val cooldownMillis: Long = 2 * 60_000,
)

/**
 * [Transport] that sends over [primary] (Bluetooth) and moves a neighbour to
 * [bulk] (Wi-Fi Direct) while a big backlog waits for it.
 *
 * The mesh above sees one link per neighbour and never knows which radio carried a
 * packet. Connected and Disconnected come from [primary] only: the Bluetooth link
 * defines who is a neighbour, and the bulk link lives inside it. If the bulk link
 * fails, sends fall back to Bluetooth; the mesh retries and dedups as always.
 *
 * Handshake, as control messages over the Bluetooth link (see [EscalationControl]):
 * ```
 * A (backlog)                  B
 *   Ask(A can host, A can join) ->
 *                              <- Offer(group)     B hosts, A joins
 *                              <- Host             or A hosts (B can only join) ...
 *   Offer(group)               ->                  ... and B joins
 *                              <- Decline          or neither works: stay on BLE
 * ```
 * Joining needs Android 10, hosting doesn't, so an Android 9 phone can still take
 * part as the host.
 */
class EscalatingTransport(
    private val self: NodeId,
    private val primary: Transport,
    private val bulk: BulkLink,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val config: EscalationConfig = EscalationConfig(),
    private val log: (String) -> Unit = {},
) : Transport {
    private enum class Stage { ASKED, HOSTING, JOINING, UP }

    private class Attempt(val stage: Stage, val generation: Int)

    // At most one entry: a phone can be in one Wi-Fi Direct group at a time.
    private val lock = Any()
    private val attempts = HashMap<NodeId, Attempt>()
    private val cooldownUntil = HashMap<NodeId, Long>()
    private val lastUsed = HashMap<NodeId, Long>()
    private var generation = 0

    private val _bulkPeers = MutableStateFlow<Set<NodeId>>(emptySet())

    /** Neighbours whose traffic goes over the bulk link right now. */
    val bulkPeers: StateFlow<Set<NodeId>> = _bulkPeers.asStateFlow()

    override val events: Flow<LinkEvent> = merge(
        primary.events.mapNotNull(::fromPrimary),
        bulk.events.mapNotNull(::fromBulk),
    )

    override suspend fun send(neighbour: NodeId, bytes: ByteArray): Boolean {
        if (stage(neighbour) == Stage.UP) {
            if (bulk.send(neighbour, bytes)) {
                touch(neighbour)
                return true
            }
            log("bulk send to $neighbour failed, back to bluetooth")
            end(neighbour)
        }
        return primary.send(neighbour, bytes)
    }

    override fun backlog(neighbour: NodeId, bytes: Int) {
        if (bytes < config.thresholdBytes) return
        val canHost = bulk.canHost()
        val canJoin = bulk.canJoin()
        if (!canHost && !canJoin) return
        val generation = synchronized(lock) {
            if (attempts.isNotEmpty()) return
            if (clock() < (cooldownUntil[neighbour] ?: 0)) return
            begin(neighbour, Stage.ASKED)
        }
        log("$bytes B waiting for $neighbour, asking to switch to wi-fi")
        scope.launch {
            if (!primary.send(neighbour, EscalationControl.encode(Control.Ask(canHost, canJoin)))) {
                fail(neighbour, generation)
            }
        }
    }

    private fun fromPrimary(event: LinkEvent): LinkEvent? {
        when (event) {
            is LinkEvent.Received -> if (EscalationControl.isControl(event.bytes)) {
                val control = try {
                    EscalationControl.decode(event.bytes)
                } catch (e: MalformedPacketException) {
                    log("bad control from ${event.neighbour}: ${e.message}")
                    return null
                }
                // Making a group takes seconds; the mesh's event loop must not wait for it.
                scope.launch { onControl(event.neighbour, control) }
                return null
            }
            is LinkEvent.Disconnected -> if (stage(event.neighbour) != null) end(event.neighbour)
            is LinkEvent.Connected -> Unit
        }
        return event
    }

    private fun fromBulk(event: BulkEvent): LinkEvent? {
        when (event) {
            is BulkEvent.Up -> onUp(event.peer)
            is BulkEvent.Down -> end(event.peer, closeLink = false)
            is BulkEvent.Received -> {
                touch(event.peer)
                return LinkEvent.Received(event.peer, event.bytes)
            }
        }
        return null
    }

    private suspend fun onControl(peer: NodeId, control: Control) {
        when (control) {
            is Control.Ask -> onAsk(peer, control)
            Control.Host -> advance(peer, setOf(Stage.ASKED), Stage.HOSTING)?.let { host(peer, it) }
            is Control.Offer -> advance(peer, setOf(Stage.ASKED, Stage.JOINING), Stage.JOINING)?.let { generation ->
                val joined = bulk.join(peer, control.offer)
                if (!current(peer, generation)) return bulk.close(peer)
                if (!joined) {
                    log("couldn't join $peer's group")
                    bulk.close(peer)
                    fail(peer, generation)
                    // So the host takes its group down now, not at the setup timeout.
                    primary.send(peer, EscalationControl.encode(Control.Decline))
                }
            }
            Control.Decline -> synchronized(lock) { attempts[peer]?.takeIf { it.stage != Stage.UP } }?.let {
                log("$peer declined wi-fi")
                bulk.close(peer)
                fail(peer, it.generation)
            }
        }
    }

    private suspend fun onAsk(peer: NodeId, ask: Control.Ask) {
        val canHost = bulk.canHost()
        val canJoin = bulk.canJoin()
        val (stage, generation) = synchronized(lock) {
            val mine = attempts[peer]
            // Both asked at once: the lower id's ask stands, the higher id answers it.
            if (mine?.stage == Stage.ASKED && Integer.compareUnsigned(self.value, peer.value) < 0) return
            if (mine != null && mine.stage != Stage.ASKED) return // already setting up with them
            attempts.remove(peer)
            when {
                attempts.isNotEmpty() -> null // busy with another neighbour
                ask.canJoin && canHost -> Stage.HOSTING to begin(peer, Stage.HOSTING)
                ask.canHost && canJoin -> Stage.JOINING to begin(peer, Stage.JOINING)
                else -> null
            }
        } ?: return run { primary.send(peer, EscalationControl.encode(Control.Decline)) }
        if (stage == Stage.HOSTING) {
            host(peer, generation)
        } else if (!primary.send(peer, EscalationControl.encode(Control.Host))) {
            fail(peer, generation)
        }
    }

    private suspend fun host(peer: NodeId, generation: Int) {
        val offer = bulk.host(peer)
        if (!current(peer, generation)) return bulk.close(peer)
        if (offer == null || !primary.send(peer, EscalationControl.encode(Control.Offer(offer)))) {
            log("couldn't host a group for $peer")
            primary.send(peer, EscalationControl.encode(Control.Decline))
            bulk.close(peer)
            fail(peer, generation)
        }
        // Now [peer] joins; Up arrives as a bulk event, or the setup timeout ends it.
    }

    private fun onUp(peer: NodeId) {
        val generation = synchronized(lock) {
            val attempt = attempts[peer]
            if (attempt == null || attempt.stage == Stage.UP) null else begin(peer, Stage.UP)
        }
        if (generation == null) return bulk.close(peer) // nobody asked for this link
        log("wi-fi link up with $peer")
        touch(peer)
        publish()
        scope.launch { closeWhenIdle(peer, generation) }
    }

    private suspend fun closeWhenIdle(peer: NodeId, generation: Int) {
        while (true) {
            delay(config.idleMillis / 4)
            val idle = synchronized(lock) {
                if (attempts[peer]?.generation != generation) return
                clock() - (lastUsed[peer] ?: 0) >= config.idleMillis
            }
            if (idle) {
                log("wi-fi link with $peer idle, closing")
                return end(peer)
            }
        }
    }

    // Caller holds lock. Every stage gets a fresh generation, so a late timer or
    // callback from an earlier stage can tell it no longer applies.
    private fun begin(peer: NodeId, stage: Stage): Int {
        val generation = ++generation
        attempts[peer] = Attempt(stage, generation)
        if (stage != Stage.UP) {
            scope.launch {
                delay(config.setupTimeoutMillis)
                if (current(peer, generation)) {
                    log("wi-fi setup with $peer timed out")
                    bulk.close(peer)
                    fail(peer, generation)
                }
            }
        }
        return generation
    }

    private fun advance(peer: NodeId, from: Set<Stage>, to: Stage): Int? = synchronized(lock) {
        val attempt = attempts[peer] ?: return null
        if (attempt.stage !in from) return null
        begin(peer, to)
    }

    private fun fail(peer: NodeId, generation: Int) {
        synchronized(lock) {
            if (attempts[peer]?.generation != generation) return
            attempts.remove(peer)
            cooldownUntil[peer] = clock() + config.cooldownMillis
        }
        publish()
    }

    /** Drops whatever we have with [peer], back to Bluetooth only. */
    private fun end(peer: NodeId, closeLink: Boolean = true) {
        val had = synchronized(lock) {
            lastUsed -= peer
            (attempts.remove(peer) != null).also { if (it) cooldownUntil[peer] = clock() + config.cooldownMillis }
        }
        if (closeLink) bulk.close(peer)
        if (had) publish()
    }

    private fun current(peer: NodeId, generation: Int) = synchronized(lock) { attempts[peer]?.generation == generation }

    private fun stage(peer: NodeId) = synchronized(lock) { attempts[peer]?.stage }

    private fun touch(peer: NodeId) = synchronized(lock) { lastUsed[peer] = clock() }

    private fun publish() {
        _bulkPeers.value = synchronized(lock) { attempts.filterValues { it.stage == Stage.UP }.keys.toSet() }
    }
}

internal sealed interface Control {
    data class Ask(val canHost: Boolean, val canJoin: Boolean) : Control

    data class Offer(val offer: BulkOffer) : Control

    /** "You host, I'll join." */
    data object Host : Control

    data object Decline : Control
}

/**
 * Control messages ride the Bluetooth link like packets, so they need a first byte
 * no packet has: packets start with their version (2).
 * ```
 * marker 0xB1 u8 | kind u8 | body
 * Ask:   flags u8 (bit 0 = can host, bit 1 = can join)
 * Offer: port u16 | network length u8 | network | passphrase length u8 | passphrase
 * Host, Decline: no body
 * ```
 */
internal object EscalationControl {
    private const val MARKER = 0xB1.toByte()
    private const val ASK = 1
    private const val OFFER = 2
    private const val HOST = 3
    private const val DECLINE = 4

    fun isControl(bytes: ByteArray) = bytes.isNotEmpty() && bytes[0] == MARKER

    fun encode(control: Control): ByteArray = when (control) {
        is Control.Ask -> byteArrayOf(MARKER, ASK.toByte(), ((if (control.canHost) 1 else 0) or (if (control.canJoin) 2 else 0)).toByte())
        is Control.Offer -> {
            val network = control.offer.network.encodeToByteArray()
            val passphrase = control.offer.passphrase.encodeToByteArray()
            require(network.size <= 255 && passphrase.size <= 255) { "offer too long" }
            ByteBuffer.allocate(6 + network.size + passphrase.size)
                .put(MARKER).put(OFFER.toByte())
                .putShort(control.offer.port.toShort())
                .put(network.size.toByte()).put(network)
                .put(passphrase.size.toByte()).put(passphrase)
                .array()
        }
        Control.Host -> byteArrayOf(MARKER, HOST.toByte())
        Control.Decline -> byteArrayOf(MARKER, DECLINE.toByte())
    }

    fun decode(bytes: ByteArray): Control {
        val buf = ByteBuffer.wrap(bytes)
        try {
            if (buf.get() != MARKER) throw MalformedPacketException("not a control message")
            val control = when (val kind = buf.get().toInt()) {
                ASK -> buf.get().toInt().let { Control.Ask(canHost = it and 1 != 0, canJoin = it and 2 != 0) }
                OFFER -> {
                    val port = buf.getShort().toUShort().toInt()
                    val network = ByteArray(buf.get().toUByte().toInt()).also { buf.get(it) }.decodeToString()
                    val passphrase = ByteArray(buf.get().toUByte().toInt()).also { buf.get(it) }.decodeToString()
                    Control.Offer(BulkOffer(network, passphrase, port))
                }
                HOST -> Control.Host
                DECLINE -> Control.Decline
                else -> throw MalformedPacketException("unknown control kind $kind")
            }
            if (buf.hasRemaining()) throw MalformedPacketException("${buf.remaining()} bytes after control message")
            return control
        } catch (e: BufferUnderflowException) {
            throw MalformedPacketException("truncated control message (${bytes.size} bytes)")
        }
    }
}
