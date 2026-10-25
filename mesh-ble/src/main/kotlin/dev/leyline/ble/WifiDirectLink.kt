package dev.leyline.ble

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import dev.leyline.mesh.BulkEvent
import dev.leyline.mesh.BulkLink
import dev.leyline.mesh.BulkOffer
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.PacketCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.resume

/**
 * [BulkLink] over Wi-Fi Direct: megabytes per second against BLE's few KB/s, for
 * more battery, so it only runs while a big backlog lasts.
 *
 * No peer discovery and no "accept connection?" dialog. The host makes a group
 * of its own (createGroup); its network name and passphrase travel over the
 * Bluetooth link, and the joiner connects with them as it would to a hotspot
 * (WifiP2pConfig.Builder, Android 10+). The group owner then listens on a TCP
 * port; the joiner connects and first sends its node id, so the host knows the
 * socket is the neighbour it made the group for.
 *
 * Stream, both ways: `length u32 | packet`, repeated. Anyone who overheard the
 * passphrase could join, but every packet is still signature-checked by the mesh,
 * exactly as over Bluetooth.
 */
@SuppressLint("MissingPermission")
class WifiDirectLink(
    private val context: Context,
    private val self: NodeId,
    private val scope: CoroutineScope,
) : BulkLink {
    private class Session(val peer: NodeId, val socket: Socket) {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
        val writeLock = Mutex()
    }

    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private val wifi: WifiManager? = context.applicationContext.getSystemService(WifiManager::class.java)

    @Volatile
    private var channel: WifiP2pManager.Channel? = null

    private val out = Channel<BulkEvent>(Channel.UNLIMITED)
    override val events: Flow<BulkEvent> = out.receiveAsFlow()

    // One group at a time: whom it is for, and what is open for them.
    private val lock = Any()
    private var busyWith: NodeId? = null
    private var session: Session? = null
    private var server: ServerSocket? = null

    fun start() {
        channel = manager?.initialize(context, Looper.getMainLooper()) { channel = null }
    }

    fun stop() {
        synchronized(lock) { busyWith }?.let(::close)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) channel?.close()
        channel = null
    }

    override fun canHost(): Boolean = ready() && synchronized(lock) { busyWith == null }

    override fun canJoin(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && canHost()

    override suspend fun host(peer: NodeId): BulkOffer? {
        val ch = channel ?: return null
        if (!claim(peer)) return null
        try {
            var failure = p2p { manager!!.createGroup(ch, it) }
            if (failure == WifiP2pManager.BUSY) {
                // Most likely a group of ours left over from a crash.
                p2p { manager!!.removeGroup(ch, it) }
                failure = p2p { manager!!.createGroup(ch, it) }
            }
            if (failure != null) throw IOException("createGroup failed: ${reason(failure)}")
            val group = awaitGroup(ch) ?: throw IOException("group never came up")
            val srv = withContext(Dispatchers.IO) { ServerSocket(0).apply { soTimeout = ACCEPT_TIMEOUT } }
            if (!keep(peer) { server = srv }) {
                srv.close()
                return null
            }
            scope.launch(Dispatchers.IO) { accept(peer, srv) }
            Log.i(TAG, "hosting ${group.networkName} for $peer on port ${srv.localPort}")
            return BulkOffer(group.networkName, group.passphrase, srv.localPort)
        } catch (e: Exception) {
            Log.w(TAG, "can't host for $peer: ${e.message}")
            close(peer)
            return null
        }
    }

    override suspend fun join(peer: NodeId, offer: BulkOffer): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val ch = channel ?: return false
        if (!claim(peer)) return false
        try {
            val config = WifiP2pConfig.Builder()
                .setNetworkName(offer.network)
                .setPassphrase(offer.passphrase)
                .build()
            p2p { manager!!.connect(ch, config, it) }?.let { throw IOException("connect failed: ${reason(it)}") }
            val owner = awaitJoined(ch) ?: throw IOException("never joined ${offer.network}")
            val socket = withContext(Dispatchers.IO) { connect(owner, offer.port) }
            val s = Session(peer, socket)
            withContext(Dispatchers.IO) {
                s.output.writeInt(self.value)
                s.output.flush()
            }
            if (!up(s)) return false
            Log.i(TAG, "joined ${offer.network} of $peer")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "can't join $peer: ${e.message}")
            close(peer)
            return false
        }
    }

    override suspend fun send(peer: NodeId, bytes: ByteArray): Boolean {
        val s = synchronized(lock) { session?.takeIf { it.peer == peer } } ?: return false
        return try {
            s.writeLock.withLock {
                withContext(Dispatchers.IO) {
                    s.output.writeInt(bytes.size)
                    s.output.write(bytes)
                    s.output.flush()
                }
            }
            true
        } catch (e: IOException) {
            Log.w(TAG, "write to $peer failed: ${e.message}")
            close(peer)
            false
        }
    }

    override fun close(peer: NodeId) {
        val (s, srv) = synchronized(lock) {
            if (busyWith != peer) return
            busyWith = null
            (session to server).also {
                session = null
                server = null
            }
        }
        runCatching { s?.socket?.close() }
        runCatching { srv?.close() }
        // Owner or member: either way this leaves the group, and for the owner ends it.
        channel?.let { manager?.removeGroup(it, null) }
        out.trySend(BulkEvent.Down(peer))
        Log.i(TAG, "closed link with $peer")
    }

    private fun accept(peer: NodeId, srv: ServerSocket) {
        try {
            val socket = srv.use { it.accept() }
            socket.soTimeout = HELLO_TIMEOUT
            val s = Session(peer, socket)
            val claimed = NodeId(s.input.readInt())
            if (claimed != peer) {
                socket.close()
                throw IOException("expected $peer, $claimed connected")
            }
            socket.soTimeout = 0
            up(s)
        } catch (e: Exception) {
            if (synchronized(lock) { busyWith == peer }) {
                Log.w(TAG, "no connection from $peer: ${e.message}")
                close(peer)
            }
        }
    }

    // Publishes a connected socket and reads from it until it breaks.
    private fun up(s: Session): Boolean {
        s.socket.tcpNoDelay = true
        if (!keep(s.peer) { session = s }) {
            runCatching { s.socket.close() }
            return false
        }
        out.trySend(BulkEvent.Up(s.peer))
        scope.launch(Dispatchers.IO) {
            try {
                while (true) {
                    val size = s.input.readInt()
                    if (size !in 1..PacketCodec.MAX_ENCODED_SIZE) throw IOException("bad length $size")
                    val bytes = ByteArray(size).also { s.input.readFully(it) }
                    out.send(BulkEvent.Received(s.peer, bytes))
                }
            } catch (e: Exception) {
                if (synchronized(lock) { session === s }) {
                    Log.i(TAG, "link with ${s.peer} ended: ${e.message}")
                    close(s.peer)
                }
            }
        }
        return true
    }

    private fun claim(peer: NodeId): Boolean = synchronized(lock) {
        if (busyWith != null) return false
        busyWith = peer
        true
    }

    // Runs [store] only if the group is still for [peer]: it may have been closed meanwhile.
    private fun keep(peer: NodeId, store: () -> Unit): Boolean = synchronized(lock) {
        if (busyWith != peer) return false
        store()
        true
    }

    // createGroup reports success before the group exists; its details come a moment later.
    private suspend fun awaitGroup(ch: WifiP2pManager.Channel): WifiP2pGroup? {
        repeat(GROUP_POLLS) {
            val group = withTimeoutOrNull(CALL_TIMEOUT) {
                suspendCancellableCoroutine<WifiP2pGroup?> { c -> manager!!.requestGroupInfo(ch) { c.resume(it) } }
            }
            if (group != null && group.isGroupOwner && !group.passphrase.isNullOrEmpty()) return group
            delay(POLL_INTERVAL)
        }
        return null
    }

    private suspend fun awaitJoined(ch: WifiP2pManager.Channel): InetAddress? {
        repeat(JOIN_POLLS) {
            val info = withTimeoutOrNull(CALL_TIMEOUT) {
                suspendCancellableCoroutine<WifiP2pInfo?> { c -> manager!!.requestConnectionInfo(ch) { c.resume(it) } }
            }
            if (info != null && info.groupFormed && !info.isGroupOwner) info.groupOwnerAddress?.let { return it }
            delay(POLL_INTERVAL)
        }
        return null
    }

    // The owner's server socket may not be listening the instant the group forms.
    private fun connect(owner: InetAddress, port: Int): Socket {
        var last: IOException? = null
        repeat(CONNECT_ATTEMPTS) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(owner, port), CONNECT_TIMEOUT)
                return socket
            } catch (e: IOException) {
                socket.close()
                last = e
                Thread.sleep(POLL_INTERVAL)
            }
        }
        throw last ?: IOException("can't connect to $owner:$port")
    }

    /** Null on success, else the failure reason. Null too if the framework never answers. */
    private suspend fun p2p(call: (WifiP2pManager.ActionListener) -> Unit): Int? = withTimeoutOrNull(CALL_TIMEOUT) {
        suspendCancellableCoroutine { c ->
            call(object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (c.isActive) c.resume(null)
                }

                override fun onFailure(reason: Int) {
                    if (c.isActive) c.resume(reason)
                }
            })
        }
    }

    private fun ready(): Boolean = channel != null && wifi?.isWifiEnabled == true && permitted(context)

    private fun reason(code: Int) = when (code) {
        WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
        WifiP2pManager.BUSY -> "busy"
        WifiP2pManager.ERROR -> "error"
        else -> "code $code"
    }

    companion object {
        private const val TAG = "LeylineWifi"
        private const val CALL_TIMEOUT = 5_000L
        private const val POLL_INTERVAL = 500L
        private const val GROUP_POLLS = 10
        private const val JOIN_POLLS = 40
        private const val ACCEPT_TIMEOUT = 25_000
        private const val HELLO_TIMEOUT = 5_000
        private const val CONNECT_TIMEOUT = 3_000
        private const val CONNECT_ATTEMPTS = 5

        /** Runtime permission Wi-Fi Direct needs on this Android version. */
        fun permission(): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }

        fun permitted(context: Context): Boolean =
            context.checkSelfPermission(permission()) == PackageManager.PERMISSION_GRANTED
    }
}
