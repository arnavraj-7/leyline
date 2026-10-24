package dev.leyline.app.data

import dev.leyline.mesh.MessageId
import dev.leyline.mesh.NodeId
import dev.leyline.mesh.Outbox
import dev.leyline.mesh.Packet
import dev.leyline.mesh.PacketCodec
import dev.leyline.mesh.Priority
import dev.leyline.mesh.StoredPacket

/**
 * The mesh's [Outbox] in SQLite, so packets being carried survive the app being
 * killed or the phone rebooting: the "store" in store-and-forward.
 */
class RoomOutbox(private val dao: OutboxDao, private val capacity: Int = 1000) : Outbox {
    override suspend fun store(packet: Packet, holders: Set<NodeId>, storedAt: Long, expiresAt: Long) =
        dao.store(entity(packet, storedAt, expiresAt), holderRows(packet, holders), capacity)

    override suspend fun upgrade(packet: Packet, holders: Set<NodeId>, storedAt: Long, expiresAt: Long) =
        dao.upgrade(entity(packet, storedAt, expiresAt), holderRows(packet, holders), capacity)

    private fun entity(packet: Packet, storedAt: Long, expiresAt: Long) = OutboxEntity(
        id = packet.id.value,
        bytes = PacketCodec.encode(packet),
        sos = packet.priority == Priority.SOS,
        storedAt = storedAt,
        expiresAt = expiresAt,
    )

    private fun holderRows(packet: Packet, holders: Set<NodeId>) = holders.map { HolderEntity(packet.id.value, it.value) }

    override suspend fun addHolders(id: MessageId, holders: Set<NodeId>) =
        dao.addHolders(id.value, holders.map { HolderEntity(id.value, it.value) })

    override suspend fun get(id: MessageId): StoredPacket? {
        val entity = dao.get(id.value) ?: return null
        return entity.toStored(dao.holders(id.value).mapTo(HashSet()) { NodeId(it) })
    }

    override suspend fun live(now: Long): List<StoredPacket> {
        val holders = dao.liveHolders(now).groupBy({ it.messageId }, { NodeId(it.node) })
        return dao.live(now).map { it.toStored(holders[it.id].orEmpty().toSet()) }
    }

    override suspend fun remove(id: MessageId) = dao.delete(id.value)

    override suspend fun purgeExpired(now: Long): Int = dao.deleteExpired(now)

    private fun OutboxEntity.toStored(holders: Set<NodeId>) =
        StoredPacket(PacketCodec.decode(bytes), storedAt, expiresAt, holders)
}
