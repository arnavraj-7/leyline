package dev.leyline.mesh

enum class PacketType(val code: Int) {
    MESSAGE(1),
    ACK(2);

    companion object {
        fun fromCode(code: Int): PacketType? = entries.firstOrNull { it.code == code }
    }
}

enum class Priority { NORMAL, SOS }

/**
 * One routed unit on the mesh.
 *
 * [ttl] is the number of hops the packet may still travel: a node that receives
 * it with ttl 1 delivers it but does not forward it. [path] lists the relays it
 * passed through, so a receiver is `path.size + 1` radio links from the origin.
 * Relays change only those two fields; [auth] covers the rest.
 */
data class Packet(
    val id: MessageId,
    val type: PacketType,
    val priority: Priority,
    val origin: NodeId,
    val destination: NodeId,
    val ttl: Int,
    val createdAt: Long,
    val path: List<NodeId>,
    val payload: ByteArray,
    /** Origin's key and signature; null only in tests and the simulator. See [PacketSignatures]. */
    val auth: PacketAuth? = null,
) {
    init {
        require(ttl in 0..MAX_TTL) { "ttl $ttl outside 0..$MAX_TTL" }
        require(path.size <= MAX_TTL) { "path longer than $MAX_TTL hops" }
        require(payload.size <= MAX_PAYLOAD) { "payload ${payload.size} bytes exceeds $MAX_PAYLOAD" }
    }

    /** Relays passed so far. A receiver is `relayCount + 1` radio links from the origin. */
    val relayCount: Int get() = path.size

    // ByteArray uses identity equality, so the generated equals would compare references.
    override fun equals(other: Any?): Boolean =
        other is Packet &&
            id == other.id && type == other.type && priority == other.priority &&
            origin == other.origin && destination == other.destination &&
            ttl == other.ttl && createdAt == other.createdAt && path == other.path &&
            payload.contentEquals(other.payload) && auth == other.auth

    override fun hashCode(): Int = id.hashCode()

    companion object {
        const val MAX_TTL = 7
        const val DEFAULT_TTL = 4
        const val MAX_PAYLOAD = 1200
    }
}
