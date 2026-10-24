package dev.leyline.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MessageEntity::class,
        ReceiptEntity::class,
        OutboxEntity::class,
        HolderEntity::class,
        KeyPinEntity::class,
        BoxKeyEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class LeylineDatabase : RoomDatabase() {
    abstract fun messages(): MessageDao
    abstract fun outbox(): OutboxDao
    abstract fun keyPins(): KeyPinDao
    abstract fun boxKeys(): BoxKeyDao

    companion object {
        fun create(context: Context): LeylineDatabase =
            Room.databaseBuilder(context, LeylineDatabase::class.java, "leyline.db")
                // Pre-release: a schema change wipes local data instead of needing a migration.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
