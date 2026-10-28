package dev.leyline.app.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation

/** A message shown in the chat: one we sent, or one delivered to us. */
@Entity(tableName = "messages", indices = [Index("receivedAt")])
data class MessageEntity(
    /** The mesh [dev.leyline.mesh.MessageId]; the primary key makes a repeat insert a no-op. */
    @PrimaryKey val id: Long,
    val origin: Int,
    val destination: Int,
    val sos: Boolean,
    val senderName: String?,
    val text: String,
    val latE7: Int?,
    val lonE7: Int?,
    val accuracyMeters: Int?,
    /** The sender's clock. Phones in a blackout can disagree, so sorting uses [receivedAt]. */
    val createdAt: Long,
    val receivedAt: Long,
    /** Radio links crossed; 0 for our own messages. */
    val hops: Int,
    /** Relay node ids in order, as hex, comma-separated. */
    val path: String,
    val outgoing: Boolean,
    /** SOS triage: [dev.leyline.mesh.SosKind] code, or null if the sender gave no details. */
    val sosKind: Int? = null,
    /** How many people need help; 0 if not given. */
    val people: Int = 0,
    val injured: Boolean = false,
    /** Set when this is a rescuer's answer to an SOS: that SOS's id. Shown on the SOS, not in the chat. */
    val respondsTo: Long? = null,
    /** [dev.leyline.mesh.ResponseStatus] code, with [respondsTo]. */
    val responseStatus: Int? = null,
    /** A private message that crossed the mesh end-to-end encrypted. */
    val encrypted: Boolean = false,
    /** A photo or voice clip: [dev.leyline.mesh.MediaKind] code, and its file in the app's media folder. */
    val mediaKind: Int? = null,
    val mediaFile: String? = null,
)

/** "Node X got message Y, Z hops away": one row per receiver of our message. */
@Entity(tableName = "receipts", primaryKeys = ["messageId", "node"])
data class ReceiptEntity(
    val messageId: Long,
    val node: Int,
    val hops: Int,
    val receivedAt: Long,
    /** The receiver's display name, carried in the receipt itself. */
    val name: String?,
)

/** How many other phones an outbox packet has been handed to (or heard back from). */
data class HolderCount(val messageId: Long, val holders: Int)

data class MessageWithReceipts(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "messageId") val receipts: List<ReceiptEntity>,
)

/** A packet carried for store-and-forward, kept in its wire encoding. */
@Entity(tableName = "outbox", indices = [Index("expiresAt")])
data class OutboxEntity(
    @PrimaryKey val id: Long,
    val bytes: ByteArray,
    val sos: Boolean,
    val storedAt: Long,
    val expiresAt: Long,
) {
    override fun equals(other: Any?) = other is OutboxEntity && id == other.id && bytes.contentEquals(other.bytes)
    override fun hashCode() = id.hashCode()
}

/** A node known to already have an outbox packet. Deleted with the packet. */
@Entity(
    tableName = "outbox_holders",
    primaryKeys = ["messageId", "node"],
    foreignKeys = [
        ForeignKey(
            entity = OutboxEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class HolderEntity(val messageId: Long, val node: Int)

/** A node's public encryption key, learned from its signed messages. First one seen wins. */
@Entity(tableName = "box_keys")
data class BoxKeyEntity(@PrimaryKey val node: Int, val publicKey: ByteArray, val firstSeen: Long) {
    override fun equals(other: Any?) = other is BoxKeyEntity && node == other.node && publicKey.contentEquals(other.publicKey)
    override fun hashCode() = node
}

/** The first public key seen for a node id (trust on first use). */
@Entity(tableName = "key_pins")
data class KeyPinEntity(@PrimaryKey val node: Int, val publicKey: ByteArray, val firstSeen: Long) {
    override fun equals(other: Any?) = other is KeyPinEntity && node == other.node && publicKey.contentEquals(other.publicKey)
    override fun hashCode() = node
}
