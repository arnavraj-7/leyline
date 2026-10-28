package dev.leyline.app.mesh

import android.content.Context
import dev.leyline.mesh.MediaChunk
import dev.leyline.mesh.MediaKind
import dev.leyline.mesh.NodeId
import java.io.File

/**
 * Photos and voice clips, which are bigger than a packet: split into
 * [MediaChunk]s to send, put back together as they arrive (in any order, from
 * any relays), and kept as files the chat shows. Pieces live in memory only;
 * a clip still incomplete after [PARTIAL_MILLIS] is dropped.
 */
class MediaStore(context: Context) {
    private class Partial(val kind: MediaKind, val parts: Array<ByteArray?>, val started: Long)

    private val dir = File(context.filesDir, "media").apply { mkdirs() }
    private val partial = HashMap<Pair<NodeId, Long>, Partial>()

    fun file(name: String) = File(dir, name)

    /** Null if [bytes] needs more than [MediaChunk.MAX_CHUNKS] pieces. */
    fun split(kind: MediaKind, id: Long, bytes: ByteArray): List<MediaChunk>? {
        val count = (bytes.size + MediaChunk.MAX_BYTES - 1) / MediaChunk.MAX_BYTES
        if (count !in 1..MediaChunk.MAX_CHUNKS) return null
        return List(count) { i ->
            MediaChunk(id, kind, i, count, bytes.copyOfRange(i * MediaChunk.MAX_BYTES, minOf(bytes.size, (i + 1) * MediaChunk.MAX_BYTES)))
        }
    }

    fun save(origin: NodeId, id: Long, kind: MediaKind, bytes: ByteArray): File =
        file(name(origin, id, kind)).apply { writeBytes(bytes) }

    /** Adds one piece. Returns the finished file when this was the last one missing, else null. */
    @Synchronized
    fun accept(origin: NodeId, chunk: MediaChunk, now: Long = System.currentTimeMillis()): File? {
        partial.values.removeAll { now - it.started > PARTIAL_MILLIS }
        val key = origin to chunk.id
        val done = file(name(origin, chunk.id, chunk.kind))
        if (done.exists()) return null
        val p = partial.getOrPut(key) { Partial(chunk.kind, arrayOfNulls(chunk.count), now) }
        if (p.parts.size != chunk.count || p.kind != chunk.kind) return null // pieces that don't agree: ignore
        p.parts[chunk.index] = chunk.bytes
        if (p.parts.any { it == null }) return null
        partial.remove(key)
        done.writeBytes(p.parts.fold(ByteArray(0)) { all, part -> all + part!! })
        return done
    }

    private fun name(origin: NodeId, id: Long, kind: MediaKind) =
        "$origin-${java.lang.Long.toHexString(id)}.${if (kind == MediaKind.PHOTO) "jpg" else "audio"}"

    private companion object {
        const val PARTIAL_MILLIS = 30 * 60_000L
    }
}
