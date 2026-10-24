package dev.leyline.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dev.leyline.mesh.LinkEvent
import dev.leyline.mesh.LinkFrame
import dev.leyline.mesh.LinkFrameCodec
import dev.leyline.mesh.MalformedPacketException
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Reassembler
import dev.leyline.mesh.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import no.nordicsemi.android.ble.ktx.suspend
import no.nordicsemi.android.ble.observer.ConnectionObserver
import no.nordicsemi.android.ble.observer.ServerObserver
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** What the radio is doing, for the mesh health screen. */
data class RadioState(
    val bluetoothOn: Boolean = false,
    val advertising: Boolean = false,
    val scanning: Boolean = false,
    val links: Int = 0,
)

/**
 * [Transport] over Bluetooth LE. Every phone is at once a peripheral (advertises,
 * runs [MeshGattServer]) and a central (scans, connects).
 *
 * Who connects: of two phones that see each other, the one with the lower node
 * id opens the connection, so a pair never builds two links at once. If that
 * doesn't happen within [FALLBACK_AFTER] (its scanner may be weak), the other
 * side connects instead; a duplicate link is resolved in [promote].
 *
 * Every link begins with a [LinkFrame.Hello] exchange, because a GATT server
 * only learns a client's rotating address, never its node id. Only after Hello
 * does the mesh hear [LinkEvent.Connected].
 *
 * Threading: Nordic and Android callbacks only post [Inbound] events; one
 * coroutine handles them in order, so link bookkeeping needs no locks. [send]
 * runs on the mesh's sender coroutines and only reads [links].
 */
@SuppressLint("MissingPermission")
class BleTransport(
    private val context: Context,
    private val self: NodeId,
    private val scope: CoroutineScope,
    /** Our display name, sent in every Hello. Read each time, so a rename applies to the next link. */
    private val name: () -> String? = { null },
) : Transport {
    private sealed interface Inbound {
        data class Sighted(val sighting: Sighting) : Inbound
        data class ServerConnected(val device: BluetoothDevice, val conn: PeerConnection) : Inbound
        data class ServerDisconnected(val device: BluetoothDevice) : Inbound
        data class ClientReady(val conn: PeerConnection, val expected: NodeId) : Inbound
        data class ConnectFailed(val node: NodeId) : Inbound
        data class Disconnected(val conn: PeerConnection) : Inbound
        class Frame(val conn: PeerConnection, val bytes: ByteArray) : Inbound
        data class HandshakeTimeout(val conn: PeerConnection) : Inbound
        data class BluetoothChanged(val on: Boolean) : Inbound
        data class SetAdvert(val info: AdvertInfo) : Inbound
        data class SetAggressive(val enabled: Boolean) : Inbound
        data object RestartScan : Inbound
    }

    private class Link(val node: NodeId, val conn: PeerConnection) {
        val reassembler = Reassembler(clock = SystemClock::elapsedRealtime)
        val sendLock = Mutex()
        var nextTransfer = 0
    }

    private class Backoff(var attempts: Int = 0, var nextAttempt: Long = 0)

    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    private val inbound = Channel<Inbound>(Channel.UNLIMITED)
    private val outbound = Channel<LinkEvent>(Channel.UNLIMITED)
    override val events: Flow<LinkEvent> = outbound.receiveAsFlow()

    // Written only by the inbound coroutine; read by send().
    private val links = ConcurrentHashMap<NodeId, Link>()

    // Inbound coroutine only, under [lock]: stop() runs on the caller's thread.
    private val lock = Any()
    private var stopped = false
    private val nodeOf = HashMap<PeerConnection, NodeId?>()
    private val serverConns = HashMap<String, PeerConnection>()
    private val connecting = HashSet<NodeId>()
    private val backoff = HashMap<NodeId, Backoff>()
    private val firstSeen = HashMap<NodeId, Long>()
    private var lastSightingsPublish = 0L
    private val latestSightings = HashMap<NodeId, Sighting>()

    @Volatile
    private var server: MeshGattServer? = null
    private var advertiser: Advertiser? = null
    private var scanner: Scanner? = null
    private var job: Job? = null
    private var advert = AdvertInfo(self, carryingSos = false, relaying = true)
    private var aggressive = true

    // The mode the running scan really uses; null when not scanning. Can lag [aggressive]
    // when Scanner refuses a restart to stay under Android's scan throttle.
    private var scanMode: Boolean? = null
    private var scanRetryPending = false

    private val _sightings = MutableStateFlow<Map<NodeId, Sighting>>(emptyMap())

    /** Leyline phones heard recently, with signal strength; feeds the live mesh map. */
    val sightings: StateFlow<Map<NodeId, Sighting>> = _sightings.asStateFlow()

    private val _names = MutableStateFlow<Map<NodeId, String>>(emptyMap())

    /** Display names neighbours sent in their Hello. */
    val names: StateFlow<Map<NodeId, String>> = _names.asStateFlow()

    private val _radio = MutableStateFlow(RadioState())
    val radio: StateFlow<RadioState> = _radio.asStateFlow()

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_ON -> inbound.trySend(Inbound.BluetoothChanged(true))
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF ->
                    inbound.trySend(Inbound.BluetoothChanged(false))
            }
        }
    }

    /** Starts radios and link management. Caller must already hold [BlePermissions.required]. */
    fun start() {
        check(job == null) { "already started" }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(bluetoothReceiver, filter)
        }
        job = scope.launch {
            launch { maintain() }
            if (adapter?.isEnabled == true) synchronized(lock) { if (!stopped) radiosUp() }
            for (event in inbound) synchronized(lock) { if (!stopped) handle(event) }
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(bluetoothReceiver) }
        job?.cancel()
        job = null
        // The inbound coroutine may be mid-event on another thread; wait for it, and make
        // sure an event it already took can't bring the radios back up afterwards.
        synchronized(lock) {
            stopped = true
            radiosDown()
        }
    }

    /** SOS beacon mode and relay flag, as advertised to phones that haven't connected yet. */
    fun setAdvert(carryingSos: Boolean, relaying: Boolean) {
        inbound.trySend(Inbound.SetAdvert(AdvertInfo(self, carryingSos, relaying)))
    }

    /** Low-latency scanning while the app is open or an SOS is live; balanced otherwise to save battery. */
    fun setAggressive(enabled: Boolean) {
        inbound.trySend(Inbound.SetAggressive(enabled))
    }

    override suspend fun send(neighbour: NodeId, bytes: ByteArray): Boolean {
        val link = links[neighbour] ?: return false
        return link.sendLock.withLock {
            val frames = LinkFrameCodec.split(bytes, link.nextTransfer++, link.conn.frameSize)
            try {
                for (frame in frames) link.conn.writeFrame(frame)
                true
            } catch (e: Exception) {
                Log.w(TAG, "write to $neighbour failed: ${e.javaClass.simpleName} ${e.message}")
                false
            }
        }
    }

    private fun handle(event: Inbound) {
        when (event) {
            is Inbound.Sighted -> onSighted(event.sighting)
            is Inbound.ServerConnected -> onServerConnected(event.device, event.conn)
            is Inbound.ServerDisconnected -> serverConns.remove(event.device.address)?.let {
                onDisconnected(it)
                it.close()
            }
            is Inbound.ClientReady -> {
                nodeOf[event.conn] = null
                scope.launch {
                    sendHello(event.conn)
                    delay(HANDSHAKE_TIMEOUT)
                    inbound.send(Inbound.HandshakeTimeout(event.conn))
                }
            }
            is Inbound.ConnectFailed -> {
                connecting -= event.node
                penalise(event.node)
            }
            is Inbound.Disconnected -> onDisconnected(event.conn)
            is Inbound.Frame -> onFrame(event.conn, event.bytes)
            is Inbound.HandshakeTimeout -> if (event.conn in nodeOf && nodeOf[event.conn] == null) {
                Log.w(TAG, "no Hello from ${event.conn.address}, dropping link")
                event.conn.disconnect().enqueue()
            }
            is Inbound.BluetoothChanged -> if (event.on) radiosUp() else radiosDown()
            is Inbound.SetAdvert -> applyAdvert(event.info)
            is Inbound.SetAggressive -> {
                aggressive = event.enabled
                if (scanMode != null && scanMode != aggressive) startScan()
            }
            Inbound.RestartScan -> {
                scanRetryPending = false
                if (adapter?.isEnabled == true && scanner != null) startScan()
            }
        }
    }

    private fun applyAdvert(info: AdvertInfo) {
        if (info == advert) return
        val urgencyChanged = info.carryingSos != advert.carryingSos
        advert = info
        val adv = advertiser ?: return
        // Interval and power can't change in place; restart only when SOS mode flips.
        if (urgencyChanged) adv.start(info, urgent = info.carryingSos) else adv.update(info)
    }

    private fun onSighted(s: Sighting) {
        val node = s.info.node
        if (node == self) return
        publishSighting(s)
        if (links.containsKey(node) || node in connecting) return
        val now = s.seenAt
        val first = firstSeen.getOrPut(node) { now }
        backoff[node]?.let { if (now < it.nextAttempt) return }
        val iLead = Integer.compareUnsigned(self.value, node.value) < 0
        if (!iLead && now - first < FALLBACK_AFTER) return
        if (links.size + connecting.size >= MAX_LINKS) return

        connecting += node
        scope.launch {
            val conn = PeerConnection(context, PeerConnection.Role.CLIENT, ::onFrameCallback)
            conn.setConnectionObserver(observerFor(conn))
            try {
                conn.connect(s.device)
                    .retry(CONNECT_RETRIES, 300)
                    .useAutoConnect(false)
                    .timeout(CONNECT_TIMEOUT)
                    .suspend()
                inbound.send(Inbound.ClientReady(conn, node))
            } catch (e: Exception) {
                Log.w(TAG, "connect to $node failed: ${e.javaClass.simpleName} ${e.message}")
                conn.close()
                inbound.send(Inbound.ConnectFailed(node))
            }
        }
    }

    // Android also reports our own outgoing connections here. Those never send
    // a Hello on this side, so they stay unpromoted and harmless.
    private fun onServerConnected(device: BluetoothDevice, conn: PeerConnection) {
        serverConns.put(device.address, conn)?.let {
            nodeOf.remove(it)
            it.close()
        }
        nodeOf[conn] = null
    }

    // Runs on the Bluetooth callback thread, so the manager is attached before the
    // client's first write (its notification subscription) can arrive.
    private fun attachServerConnection(device: BluetoothDevice) {
        val srv = server ?: return
        val conn = PeerConnection(context, PeerConnection.Role.SERVER, ::onFrameCallback)
        conn.useServer(srv)
        conn.attachClientConnection(device)
        inbound.trySend(Inbound.ServerConnected(device, conn))
    }

    private fun onFrame(conn: PeerConnection, bytes: ByteArray) {
        if (conn !in nodeOf) return
        val frame = try {
            LinkFrameCodec.decode(bytes)
        } catch (e: MalformedPacketException) {
            Log.w(TAG, "bad frame from ${conn.address}: ${e.message}")
            return
        }
        when (frame) {
            is LinkFrame.Hello -> {
                if (frame.node == self) return
                frame.name?.let { _names.value = _names.value + (frame.node to it) }
                // Always answer. A Hello on a link we think is live means the peer's app
                // restarted and reused the connection its Bluetooth stack kept open.
                if (conn.role == PeerConnection.Role.SERVER) scope.launch { sendHello(conn) }
                if (nodeOf[conn] == frame.node) restartSession(conn, frame.node) else promote(conn, frame.node)
            }
            is LinkFrame.Data -> {
                val node = nodeOf[conn] ?: return // data before Hello
                val link = links[node]?.takeIf { it.conn === conn } ?: return
                val packet = link.reassembler.accept(frame) ?: return
                outbound.trySend(LinkEvent.Received(node, packet))
            }
        }
    }

    private fun promote(conn: PeerConnection, node: NodeId) {
        if (nodeOf[conn] == node) return
        nodeOf[conn] = node
        connecting -= node
        firstSeen -= node
        backoff -= node
        val existing = links[node]
        if (existing != null && existing.conn !== conn) {
            // Both phones connected at once. Keep the link the lower id opened, on both sides.
            val lowerIsClient = Integer.compareUnsigned(self.value, node.value) < 0
            val preferred = if (lowerIsClient) PeerConnection.Role.CLIENT else PeerConnection.Role.SERVER
            val (keep, drop) = if (conn.role == preferred) conn to existing.conn else existing.conn to conn
            if (keep === conn) links[node] = Link(node, conn)
            nodeOf[drop] = null
            if (drop.role == PeerConnection.Role.CLIENT) drop.disconnect().enqueue()
            return // the mesh already knows this neighbour
        }
        links[node] = Link(node, conn)
        publishRadio()
        outbound.trySend(LinkEvent.Connected(node))
        Log.i(TAG, "link up: $node as ${conn.role}, frame ${conn.frameSize} B")
    }

    // Same neighbour, new app instance: its transfer ids start again at 0 and it has
    // forgotten what we queued for it, so start a fresh session and let the mesh resync.
    private fun restartSession(conn: PeerConnection, node: NodeId) {
        if (links[node]?.conn !== conn) return
        links[node] = Link(node, conn)
        outbound.trySend(LinkEvent.Connected(node))
        Log.i(TAG, "session restarted: $node")
    }

    private fun onDisconnected(conn: PeerConnection) {
        val node = nodeOf.remove(conn) ?: return
        val link = links[node]
        if (link?.conn !== conn) return
        links.remove(node)
        backoff[node] = Backoff(nextAttempt = SystemClock.elapsedRealtime() + RECONNECT_DELAY)
        publishRadio()
        outbound.trySend(LinkEvent.Disconnected(node))
        Log.i(TAG, "link down: $node")
    }

    // Exponential backoff with jitter after failures (GATT 133, 8, 22 ...): 1 s, 2 s, 4 s ... 30 s.
    private fun penalise(node: NodeId) {
        val b = backoff.getOrPut(node) { Backoff() }
        b.attempts++
        val delayMs = (1_000L shl (b.attempts - 1).coerceAtMost(5)).coerceAtMost(30_000)
        b.nextAttempt = SystemClock.elapsedRealtime() + delayMs + Random.nextLong(500)
    }

    private suspend fun sendHello(conn: PeerConnection) {
        try {
            conn.writeFrame(LinkFrameCodec.encode(LinkFrame.Hello(self, name = name())))
        } catch (e: Exception) {
            Log.w(TAG, "hello to ${conn.address} failed: ${e.message}")
        }
    }

    private fun onFrameCallback(conn: PeerConnection, bytes: ByteArray) {
        inbound.trySend(Inbound.Frame(conn, bytes))
    }

    private fun observerFor(conn: PeerConnection) = object : ConnectionObserver {
        override fun onDeviceConnecting(device: BluetoothDevice) = Unit
        override fun onDeviceConnected(device: BluetoothDevice) = Unit
        override fun onDeviceFailedToConnect(device: BluetoothDevice, reason: Int) = Unit
        override fun onDeviceReady(device: BluetoothDevice) = Unit
        override fun onDeviceDisconnecting(device: BluetoothDevice) = Unit
        override fun onDeviceDisconnected(device: BluetoothDevice, reason: Int) {
            inbound.trySend(Inbound.Disconnected(conn))
            conn.close()
        }
    }

    private fun radiosUp() {
        val adapter = adapter ?: return
        if (server == null) {
            server = MeshGattServer(context).also { srv ->
                srv.setServerObserver(object : ServerObserver {
                    override fun onServerReady() = Unit
                    override fun onDeviceConnectedToServer(device: BluetoothDevice) = attachServerConnection(device)
                    override fun onDeviceDisconnectedFromServer(device: BluetoothDevice) {
                        inbound.trySend(Inbound.ServerDisconnected(device))
                    }
                })
                // Server first: a phone that finds our advert must find the service too.
                if (!srv.open()) Log.w(TAG, "GATT server failed to open")
            }
        }
        advertiser = (advertiser ?: Advertiser(adapter)).also { it.start(advert, urgent = advert.carryingSos) }
        scanner = scanner ?: Scanner(adapter) { inbound.trySend(Inbound.Sighted(it)) }
        startScan()
        _radio.value = RadioState(bluetoothOn = true, advertising = true, scanning = true, links = links.size)
    }

    private fun startScan() {
        if (scanner?.start(aggressive) == true) {
            scanMode = aggressive
            return
        }
        // Refused (too many starts in 30 s): the old mode keeps running, so try again later.
        if (!scanRetryPending) {
            scanRetryPending = true
            scope.launch {
                delay(SCAN_RETRY)
                inbound.send(Inbound.RestartScan)
            }
        }
    }

    private fun radiosDown() {
        scanner?.stop()
        scanMode = null
        advertiser?.stop()
        server?.close()
        server = null
        for (node in links.keys.toList()) {
            links.remove(node)
            outbound.trySend(LinkEvent.Disconnected(node))
        }
        nodeOf.keys.forEach { it.close() }
        nodeOf.clear()
        serverConns.clear()
        connecting.clear()
        _radio.value = RadioState(bluetoothOn = adapter?.isEnabled == true)
    }

    // Android quietly downgrades scans running over 30 minutes to opportunistic
    // (results only when another app scans). Restarting every 10 minutes avoids it.
    private suspend fun maintain() {
        while (true) {
            delay(SCAN_RESTART)
            // Through the inbound queue: Scanner and scanMode belong to that coroutine.
            inbound.send(Inbound.RestartScan)
        }
    }

    private fun publishSighting(s: Sighting) {
        latestSightings[s.info.node] = s
        if (s.seenAt - lastSightingsPublish < 1_000) return
        lastSightingsPublish = s.seenAt
        latestSightings.values.removeAll { s.seenAt - it.seenAt > SIGHTING_TTL }
        _sightings.value = latestSightings.toMap()
    }

    private fun publishRadio() {
        _radio.value = _radio.value.copy(links = links.size)
    }

    private companion object {
        const val TAG = "LeylineBle"
        const val MAX_LINKS = 6
        const val CONNECT_RETRIES = 2
        const val CONNECT_TIMEOUT = 12_000L
        const val HANDSHAKE_TIMEOUT = 5_000L
        const val FALLBACK_AFTER = 15_000L
        const val RECONNECT_DELAY = 1_000L
        const val SIGHTING_TTL = 30_000L
        const val SCAN_RETRY = 30_000L
        const val SCAN_RESTART = 10 * 60_000L
    }
}
