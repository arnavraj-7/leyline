package dev.leyline.mesh

import kotlinx.coroutines.flow.Flow

/** Something that happened on a link to a neighbour. */
sealed interface LinkEvent {
    val neighbour: NodeId

    /** The link is up and both sides have exchanged [LinkFrame.Hello]. */
    data class Connected(override val neighbour: NodeId) : LinkEvent

    data class Disconnected(override val neighbour: NodeId) : LinkEvent

    /** One whole encoded [Packet], already reassembled from frames. */
    class Received(override val neighbour: NodeId, val bytes: ByteArray) : LinkEvent
}

/**
 * Moves whole encoded packets to and from direct neighbours. BLE on phones
 * ([dev.leyline.ble]), an in-memory network in the simulator. Framing, MTU,
 * connection retries and the Hello handshake all live below this line, so the
 * mesh logic above it is identical on both.
 */
interface Transport {
    val events: Flow<LinkEvent>

    /**
     * Sends [bytes] to [neighbour] and suspends until the link layer confirms
     * the write. Returns false if the neighbour is gone or the write failed.
     */
    suspend fun send(neighbour: NodeId, bytes: ByteArray): Boolean
}
