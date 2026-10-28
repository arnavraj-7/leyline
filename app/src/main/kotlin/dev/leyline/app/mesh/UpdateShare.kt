package dev.leyline.app.mesh

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.leyline.mesh.EscalatingTransport
import dev.leyline.mesh.LinkEvent
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

sealed interface UpdateState {
    data object None : UpdateState

    /** [onWifi] false means we're waiting for a Wi-Fi Direct link: the APK never goes over BLE. */
    data class Downloading(val version: Int, val from: NodeId, val received: Int, val size: Int, val onWifi: Boolean) : UpdateState

    data class Ready(val version: Int, val from: NodeId) : UpdateState

    data class Failed(val version: Int, val reason: String) : UpdateState
}

/**
 * App updates phone to phone, no internet: one phone gets a newer build and it
 * spreads through the mesh, each phone serving the next.
 *
 * Every neighbour is told our version when a link comes up (Have). A phone with
 * an older one asks for the APK (Get) and the newer one streams it (Chunk), but
 * only over Wi-Fi Direct: 35 MB would take an hour on BLE and starve SOS traffic.
 * The bulk link is the one [EscalatingTransport] already makes for big backlogs.
 *
 * These are link frames, not mesh packets: they go one hop, are never stored or
 * relayed, and start with [MARKER], a byte no packet starts with. Before offering
 * to install, the file must match the SHA-256 the sender announced, be this app,
 * carry the announced version and be signed by the same key as the installed
 * app. Android checks that last one again at install, so a stranger can't push a
 * fake build. The user still taps Install: Android never lets an app update
 * itself silently.
 */
class UpdateShare(private val context: Context, private val log: (String) -> Unit) {
    private class Offer(val version: Int, val file: File, val size: Int, val sha: ByteArray)

    private class Wanted(val version: Int, val size: Int, val sha: ByteArray, var from: NodeId, var lastActivity: Long)

    private val dir = File(context.filesDir, "updates").apply { mkdirs() }
    val installed: Int = versionOf(context.packageManager.getPackageInfo(context.packageName, 0))

    private val lock = Any()
    private var serving: Offer? = null
    private var wanted: Wanted? = null
    private val neighbours = HashSet<NodeId>()
    private val haves = HashMap<NodeId, Int>()
    private val streaming = HashSet<NodeId>()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    init {
        // A build downloaded but not installed yet stays ready; anything older than us is done with.
        fun versionIn(file: File) = file.name.removePrefix("v").substringBefore('.').toIntOrNull()
        dir.listFiles()?.forEach { file ->
            val version = versionIn(file)
            if (version == null || version <= installed) file.delete()
        }
        // A .apk is only ever renamed from a .part after it passed every check in finish().
        dir.listFiles { f -> f.name.endsWith(".apk") }?.maxByOrNull { versionIn(it)!! }?.let { file ->
            val version = versionIn(file)!!
            serving = Offer(version, file, file.length().toInt(), sha256(file))
            _state.value = UpdateState.Ready(version, NodeId.BROADCAST)
        }
    }

    /** Wraps the session's link: update frames are handled here, everything else goes up to the mesh. */
    fun attach(link: EscalatingTransport, scope: CoroutineScope): Transport {
        val inbox = Channel<Pair<NodeId, ByteArray>>(Channel.UNLIMITED)
        // One reader, so chunks are written in the order they arrived.
        scope.launch(Dispatchers.IO) {
            for ((peer, bytes) in inbox) {
                runCatching { onFrame(link, scope, peer, bytes) }.onFailure { log("bad update frame from $peer: ${it.message}") }
            }
        }
        scope.launch { watchStalls(link) }
        return object : Transport {
            override val events: Flow<LinkEvent> = link.events.mapNotNull { event ->
                when (event) {
                    is LinkEvent.Connected -> {
                        synchronized(lock) { neighbours += event.neighbour }
                        scope.launch(Dispatchers.IO) { sendHave(link, event.neighbour) }
                    }
                    is LinkEvent.Disconnected -> synchronized(lock) { neighbours -= event.neighbour }
                    is LinkEvent.Received -> if (event.bytes.isNotEmpty() && event.bytes[0] == MARKER) {
                        inbox.trySend(event.neighbour to event.bytes)
                        return@mapNotNull null
                    }
                }
                event
            }

            override suspend fun send(neighbour: NodeId, bytes: ByteArray) = link.send(neighbour, bytes)

            override fun backlog(neighbour: NodeId, bytes: Int) = link.backlog(neighbour, bytes)
        }
    }

    /** Opens Android's installer for the downloaded build, or the "install unknown apps" switch first. */
    fun install() {
        val ready = state.value as? UpdateState.Ready ?: return
        val intent = if (!context.packageManager.canRequestPackageInstalls()) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", File(dir, "v${ready.version}.apk"))
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_TYPE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun onFrame(link: EscalatingTransport, scope: CoroutineScope, peer: NodeId, bytes: ByteArray) {
        val buf = ByteBuffer.wrap(bytes, 1, bytes.size - 1)
        val kind = buf.get().toInt()
        val version = buf.getInt()
        when (kind) {
            HAVE -> onHave(link, peer, version, size = buf.getInt(), sha = ByteArray(32).also { buf.get(it) })
            GET -> {
                val offset = buf.getInt()
                scope.launch(Dispatchers.IO) { stream(link, peer, version, offset) }
            }
            CHUNK -> onChunk(link, peer, version, buf.getInt(), ByteArray(buf.remaining()).also { buf.get(it) })
        }
    }

    private suspend fun onHave(link: EscalatingTransport, peer: NodeId, version: Int, size: Int, sha: ByteArray) {
        val offset = synchronized(lock) {
            haves[peer] = version
            val newest = maxOf(installed, serving?.version ?: 0, wanted?.version ?: 0)
            if (version <= newest) return
            log("$peer has version $version, we have $installed: fetching")
            wanted = Wanted(version, size, sha, peer, System.currentTimeMillis())
            part(version).length().toInt()
        }
        publish(link)
        link.send(peer, get(version, offset))
    }

    private suspend fun onChunk(link: EscalatingTransport, peer: NodeId, version: Int, offset: Int, data: ByteArray) {
        val want = synchronized(lock) { wanted?.takeIf { it.version == version } } ?: return
        val part = part(version)
        if (offset.toLong() != part.length()) return // a resent stream; the stall check asks again from the right place
        FileOutputStream(part, true).use { it.write(data) }
        want.lastActivity = System.currentTimeMillis()
        want.from = peer
        val received = offset + data.size
        if (received < want.size) {
            if (received / CHUNK_SIZE % PROGRESS_EVERY == 0) publish(link)
            return
        }
        finish(link, want, part)
    }

    private suspend fun finish(link: EscalatingTransport, want: Wanted, part: File) {
        val problem = when {
            !sha256(part).contentEquals(want.sha) -> "file damaged on the way"
            else -> checkArchive(part, want.version)
        }
        synchronized(lock) { wanted = null }
        if (problem != null) {
            part.delete()
            log("update ${want.version} rejected: $problem")
            _state.value = UpdateState.Failed(want.version, problem)
            return
        }
        val apk = File(dir, "v${want.version}.apk")
        part.renameTo(apk)
        val offer = Offer(want.version, apk, want.size, want.sha)
        val peers = synchronized(lock) {
            serving = offer
            neighbours.toList()
        }
        _state.value = UpdateState.Ready(want.version, want.from)
        log("update ${want.version} ready, offering it on")
        // Now we can serve it too: that is how it spreads.
        peers.forEach { link.send(it, have(offer)) }
    }

    private suspend fun stream(link: EscalatingTransport, peer: NodeId, version: Int, offset: Int) {
        val offer = offer() ?: return
        if (offer.version != version || offset !in 0..offer.size) return
        if (!synchronized(lock) { streaming.add(peer) }) return
        try {
            if (offer.size - offset >= WIFI_ONLY_ABOVE) {
                link.backlog(peer, offer.size - offset) // asks the neighbour to bring Wi-Fi Direct up
                withTimeoutOrNull(WIFI_WAIT_MILLIS) { link.bulkPeers.first { peer in it } }
                    ?: return log("no wi-fi link with $peer, not sending the update over bluetooth")
            }
            log("sending version $version to $peer from byte $offset")
            FileInputStream(offer.file).use { input ->
                input.skip(offset.toLong())
                val buffer = ByteArray(CHUNK_SIZE)
                var position = offset
                while (position < offer.size) {
                    if (offer.size - position >= WIFI_ONLY_ABOVE && peer !in link.bulkPeers.value) return
                    val n = input.read(buffer)
                    if (n <= 0) return
                    if (!link.send(peer, chunk(version, position, buffer, n))) return
                    position += n
                }
            }
        } finally {
            synchronized(lock) { streaming.remove(peer) }
        }
    }

    // A transfer that went quiet (link dropped, Wi-Fi refused) resumes from where it stopped,
    // from any neighbour that has the version.
    private suspend fun watchStalls(link: EscalatingTransport) {
        while (true) {
            delay(STALL_CHECK_MILLIS)
            val (want, source) = synchronized(lock) {
                val want = wanted ?: continue
                if (System.currentTimeMillis() - want.lastActivity < STALL_MILLIS) continue
                val source = want.from.takeIf { it in neighbours && haves[it] == want.version }
                    ?: neighbours.firstOrNull { haves[it] == want.version }
                    ?: continue
                want.from = source
                want.lastActivity = System.currentTimeMillis()
                want to source
            }
            publish(link)
            link.send(source, get(want.version, part(want.version).length().toInt()))
        }
    }

    private suspend fun sendHave(link: EscalatingTransport, peer: NodeId) {
        val offer = offer() ?: return
        link.send(peer, have(offer))
    }

    private fun publish(link: EscalatingTransport) {
        val want = synchronized(lock) { wanted } ?: return
        _state.value = UpdateState.Downloading(
            want.version, want.from, part(want.version).length().toInt(), want.size, onWifi = want.from in link.bulkPeers.value,
        )
    }

    /** What we can hand out: a downloaded newer build, else the APK we run from. */
    private fun offer(): Offer? = synchronized(lock) {
        serving ?: run {
            // A split install (Play, Android Studio) has no single APK to pass on.
            if (!context.applicationInfo.splitSourceDirs.isNullOrEmpty()) return null
            val apk = File(context.applicationInfo.sourceDir)
            Offer(installed, apk, apk.length().toInt(), sha256(apk)).also { serving = it }
        }
    }

    /** Null if [file] is a build we may install over ourselves, else why not. */
    private fun checkArchive(file: File, version: Int): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else 0
        val info = pm.getPackageArchiveInfo(file.path, flags) ?: return "not an app"
        if (info.packageName != context.packageName) return "a different app"
        if (versionOf(info) != version) return "version doesn't match what was announced"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val theirs = info.signingInfo?.apkContentsSigners
            val ours = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
            if (theirs != null && ours != null && !theirs.contentEquals(ours)) return "signed by someone else"
        }
        return null
    }

    private fun part(version: Int) = File(dir, "v$version.part")

    private companion object {
        const val MARKER = 0xA7.toByte()
        const val HAVE = 1
        const val GET = 2
        const val CHUNK = 3

        /** Fits a Wi-Fi frame, which carries at most one encoded packet's worth. */
        const val CHUNK_SIZE = 1_200
        const val PROGRESS_EVERY = 256
        const val WIFI_ONLY_ABOVE = 24 * 1024
        const val WIFI_WAIT_MILLIS = 40_000L
        const val STALL_CHECK_MILLIS = 10_000L
        const val STALL_MILLIS = 45_000L
        const val APK_TYPE = "application/vnd.android.package-archive"

        fun have(offer: Offer): ByteArray = ByteBuffer.allocate(2 + 4 + 4 + 32)
            .put(MARKER).put(HAVE.toByte()).putInt(offer.version).putInt(offer.size).put(offer.sha).array()

        fun get(version: Int, offset: Int): ByteArray = ByteBuffer.allocate(2 + 4 + 4)
            .put(MARKER).put(GET.toByte()).putInt(version).putInt(offset).array()

        fun chunk(version: Int, offset: Int, data: ByteArray, length: Int): ByteArray = ByteBuffer.allocate(2 + 4 + 4 + length)
            .put(MARKER).put(CHUNK.toByte()).putInt(version).putInt(offset).put(data, 0, length).array()

        fun sha256(file: File): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest()
        }

        @Suppress("DEPRECATION")
        fun versionOf(info: PackageInfo): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt() else info.versionCode
    }
}
