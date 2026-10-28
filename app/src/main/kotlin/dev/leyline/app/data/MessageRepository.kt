package dev.leyline.app.data

import dev.leyline.mesh.MalformedPacketException
import dev.leyline.mesh.MeshEvent
import dev.leyline.mesh.MessageBody
import dev.leyline.mesh.MessageId
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Packet
import dev.leyline.mesh.Priority
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Chat history: what the UI shows. Separate from the outbox, which is what the mesh carries. */
class MessageRepository(private val db: LeylineDatabase) {
    private val dao = db.messages()

    val recent: Flow<List<MessageWithReceipts>> = dao.recent()

    val outboxSize: Flow<Int> = db.outbox().size()

    /** Per message, how many other phones already have it: "handed to 2 phones". */
    fun handedTo(self: NodeId): Flow<Map<Long, Int>> =
        db.outbox().holderCounts(self.value).map { rows -> rows.associate { it.messageId to it.holders } }

    suspend fun knownIds(): List<MessageId> = dao.recentIds().map(::MessageId)

    /** [body] is what we wrote; for a sealed private message, the plaintext rather than what went on air. */
    suspend fun recordOutgoing(packet: Packet, body: MessageBody, now: Long, encrypted: Boolean = false, media: Pair<Int, String>? = null) {
        dao.insert(packet.toEntity(body, receivedAt = now, hops = 0, outgoing = true).copy(encrypted = encrypted, mediaKind = media?.first, mediaFile = media?.second))
    }

    /**
     * Stores a delivered message as [body] (already decrypted if it was sealed).
     * Returns false if it was already stored: the primary key is the last line of
     * dedup, so even if the mesh delivered a repeat, the user is never shown or
     * notified twice.
     */
    suspend fun recordDelivered(event: MeshEvent.Delivered, body: MessageBody, encrypted: Boolean, media: Pair<Int, String>? = null): Boolean =
        dao.insert(
            event.packet.toEntity(body, event.receivedAt, event.hops, outgoing = false)
                .copy(encrypted = encrypted, mediaKind = media?.first, mediaFile = media?.second),
        ) != -1L

    /** Remembers [node]'s encryption key; a later, different key for the same node is ignored. */
    suspend fun rememberBoxKey(node: NodeId, key: ByteArray, now: Long) {
        db.boxKeys().remember(BoxKeyEntity(node.value, key, now))
    }

    suspend fun boxKey(node: NodeId): ByteArray? = db.boxKeys().key(node.value)

    suspend fun isOwnSos(id: Long, since: Long): Boolean = dao.isOwnSos(id, since)

    suspend fun recordReceipt(event: MeshEvent.Receipt) {
        dao.insertReceipt(ReceiptEntity(event.messageId.value, event.by.value, event.hops, event.receivedAt, event.name))
    }

    suspend fun clearHistory() {
        dao.clearReceipts()
        dao.clearMessages()
        db.outbox().clear()
    }

    private fun Packet.toEntity(body: MessageBody, receivedAt: Long, hops: Int, outgoing: Boolean) = MessageEntity(
        id = id.value,
        origin = origin.value,
        destination = destination.value,
        sos = priority == Priority.SOS,
        senderName = body.senderName,
        text = body.text,
        latE7 = body.location?.latE7,
        lonE7 = body.location?.lonE7,
        accuracyMeters = body.location?.accuracyMeters,
        createdAt = createdAt,
        receivedAt = receivedAt,
        hops = hops,
        path = path.joinToString(",") { it.toString() },
        outgoing = outgoing,
        sosKind = body.details?.kind?.code,
        people = body.details?.people ?: 0,
        injured = body.details?.injured ?: false,
        respondsTo = body.response?.sos?.value,
        responseStatus = body.response?.status?.code,
    )
}
