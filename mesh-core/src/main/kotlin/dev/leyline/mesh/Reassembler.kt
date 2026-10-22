package dev.leyline.mesh

/**
 * Rebuilds packets from the [LinkFrame.Data] frames of one link.
 *
 * Frames from a radio can be repeated (a retried write), and a link can drop in
 * the middle of a transfer, so this tolerates duplicates and forgets partial
 * transfers after [timeoutMillis]. Memory is bounded: at most [maxPending]
 * transfers in progress and [maxBytes] per transfer, so a broken or hostile
 * peer can't make us buffer without limit.
 *
 * One instance per link. Not thread-safe.
 */
class Reassembler(
    private val clock: () -> Long,
    private val timeoutMillis: Long = 10_000,
    private val maxPending: Int = 4,
    private val maxBytes: Int = PacketCodec.MAX_ENCODED_SIZE,
) {
    private class Partial(val count: Int, val startedAt: Long) {
        val chunks = arrayOfNulls<ByteArray>(count)
        var received = 0
        var bytes = 0
    }

    // Insertion order = start order, so the eldest partial is evicted first.
    private val pending = LinkedHashMap<Int, Partial>()

    val pendingTransfers: Int get() = pending.size

    /** Returns the complete packet bytes when [frame] was the last missing piece, else null. */
    fun accept(frame: LinkFrame.Data): ByteArray? {
        val now = clock()
        pending.values.removeAll { now - it.startedAt >= timeoutMillis }

        if (frame.count == 1) {
            pending.remove(frame.transferId)
            return frame.chunk.takeIf { it.size <= maxBytes }
        }

        var partial = pending[frame.transferId]
        if (partial != null && partial.count != frame.count) {
            // Transfer ids wrap at 65536; a mismatched count means a new transfer reused the id.
            pending.remove(frame.transferId)
            partial = null
        }
        if (partial == null) {
            if (pending.size >= maxPending) pending.remove(pending.keys.first())
            partial = Partial(frame.count, now)
            pending[frame.transferId] = partial
        }

        if (partial.chunks[frame.index] != null) return null // duplicate frame
        partial.bytes += frame.chunk.size
        if (partial.bytes > maxBytes) {
            pending.remove(frame.transferId)
            return null
        }
        partial.chunks[frame.index] = frame.chunk
        partial.received++
        if (partial.received < partial.count) return null

        pending.remove(frame.transferId)
        val out = ByteArray(partial.bytes)
        var offset = 0
        for (chunk in partial.chunks) {
            chunk!!.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    fun clear() = pending.clear()
}
