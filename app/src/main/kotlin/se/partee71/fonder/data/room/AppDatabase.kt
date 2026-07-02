package se.partee71.fonder.data.room

import androidx.room.Database
import androidx.room.RoomDatabase
import se.partee71.fonder.data.room.daos.FundDao
import se.partee71.fonder.data.room.daos.TransactionDao
import se.partee71.fonder.data.room.entities.FundEntity
import se.partee71.fonder.data.room.entities.TransactionEntity

@Database(
    entities = [FundEntity::class, TransactionEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fundDao(): FundDao
    abstract fun transactionDao(): TransactionDao

    companion object {
        const val NAME = "fonder.db"

        // Framtida migreringar samlas här (se skill room-migrations). Version 1 = grund.
        val MIGRATIONS = emptyArray<androidx.room.migration.Migration>()
    }
}
