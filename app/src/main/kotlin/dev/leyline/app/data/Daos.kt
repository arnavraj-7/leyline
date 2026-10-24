package dev.leyline.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReceipt(receipt: ReceiptEntity)

    @Transaction
    @Query("SELECT * FROM messages ORDER BY receivedAt DESC LIMIT :limit")
    fun recent(limit: Int = 500): Flow<List<MessageWithReceipts>>

    /** Ids handled before a restart, so the mesh doesn't deliver them twice. */
    @Query("SELECT id FROM messages ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun recentIds(limit: Int = 10_000): List<Long>

    /** True if [id] is an SOS this phone sent after [since]: the only kind a rescuer may ring. */
    @Query("SELECT EXISTS(SELECT 1 FROM messages WHERE id = :id AND outgoing = 1 AND sos = 1 AND createdAt > :since)")
    suspend fun isOwnSos(id: Long, since: Long): Boolean

    @Query("DELETE FROM messages")
    suspend fun clearMessages()

    @Query("DELETE FROM receipts")
    suspend fun clearReceipts()
}

@Dao
abstract class OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insert(entity: OutboxEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertHolders(holders: List<HolderEntity>)

    @Query("SELECT COUNT(*) FROM outbox WHERE id = :id")
    protected abstract suspend fun count(id: Long): Int

    @Query("SELECT * FROM outbox WHERE id = :id")
    abstract suspend fun get(id: Long): OutboxEntity?

    @Query("SELECT node FROM outbox_holders WHERE messageId = :id")
    abstract suspend fun holders(id: Long): List<Int>

    @Query("SELECT * FROM outbox WHERE expiresAt > :now ORDER BY sos DESC, storedAt ASC")
    abstract suspend fun live(now: Long): List<OutboxEntity>

    @Query("SELECT h.* FROM outbox_holders h JOIN outbox o ON o.id = h.messageId WHERE o.expiresAt > :now")
    abstract suspend fun liveHolders(now: Long): List<HolderEntity>

    @Query("SELECT COUNT(*) FROM outbox")
    abstract fun size(): Flow<Int>

    @Query("SELECT messageId, COUNT(*) AS holders FROM outbox_holders WHERE node != :self GROUP BY messageId")
    abstract fun holderCounts(self: Int): Flow<List<HolderCount>>

    @Query("SELECT EXISTS(SELECT 1 FROM outbox WHERE sos = 1 AND expiresAt > :now)")
    abstract suspend fun carryingSos(now: Long): Boolean

    @Query("DELETE FROM outbox WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query("DELETE FROM outbox WHERE expiresAt <= :now")
    abstract suspend fun deleteExpired(now: Long): Int

    // Over capacity: drop the oldest NORMAL packets first, SOS only if nothing else is left.
    @Query(
        """DELETE FROM outbox WHERE id IN (
            SELECT id FROM outbox ORDER BY sos ASC, storedAt ASC
            LIMIT MAX(0, (SELECT COUNT(*) FROM outbox) - :capacity))""",
    )
    protected abstract suspend fun trim(capacity: Int)

    @Query("DELETE FROM outbox")
    abstract suspend fun clear()

    @Query("UPDATE outbox SET bytes = :bytes WHERE id = :id")
    protected abstract suspend fun replaceBytes(id: Long, bytes: ByteArray): Int

    @Query("DELETE FROM outbox_holders WHERE messageId = :id")
    protected abstract suspend fun clearHolders(id: Long)

    /** Swaps in a copy with more hops left; its holders start over. Inserts if absent. */
    @Transaction
    open suspend fun upgrade(entity: OutboxEntity, holders: List<HolderEntity>, capacity: Int) {
        if (replaceBytes(entity.id, entity.bytes) == 0) {
            store(entity, holders, capacity)
            return
        }
        clearHolders(entity.id)
        insertHolders(holders)
    }

    /** Insert-or-merge in one transaction, so a crash can't leave a packet without its holders. */
    @Transaction
    open suspend fun store(entity: OutboxEntity, holders: List<HolderEntity>, capacity: Int) {
        val inserted = insert(entity) != -1L
        insertHolders(holders)
        if (inserted) trim(capacity)
    }

    // The foreign key rejects holders of a packet we don't store, so check first.
    @Transaction
    open suspend fun addHolders(id: Long, holders: List<HolderEntity>) {
        if (count(id) > 0) insertHolders(holders)
    }
}

@Dao
interface BoxKeyDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun remember(key: BoxKeyEntity)

    @Query("SELECT publicKey FROM box_keys WHERE node = :node")
    suspend fun key(node: Int): ByteArray?
}

@Dao
interface KeyPinDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun pin(pin: KeyPinEntity)

    @Query("SELECT publicKey FROM key_pins WHERE node = :node")
    suspend fun key(node: Int): ByteArray?
}
