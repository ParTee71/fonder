package se.partee71.fonder.data.room

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * `CREATE`-satserna för en **frusen** schemaversion 2–14, skrivna för hand en gång här — de
 * versionerna har ingen incheckad `schemas/…/<version>.json` (det har 15 och senare, se
 * [exportedSchemaStatements]). Varje tabell beskrivs som kolumnerna i den ordning de lades till,
 * och en kolumn tas med från den version som införde den; satserna blir därmed exakt det schema
 * migreringstestet för versionen tidigare skrev av för sig (skill `room-migrations`: aldrig
 * `MigrationTestHelper`, aldrig den nuvarande entiteten). Bara tabellerna som migreringarna
 * 2→15 rör finns med — som förut.
 */
internal fun legacySchemaStatements(version: Int): List<String> {
    require(version in 2..14) { "Version $version: använd exportedSchemaStatements (15+) eller skriv v1 för hand" }
    fun table(name: String, primaryKey: String?, vararg columns: Pair<Int, String>): String {
        val definitions = columns.filter { (since, _) -> version >= since }.map { it.second } +
            listOfNotNull(primaryKey?.let { "PRIMARY KEY($it)" })
        return "CREATE TABLE `$name` (${definitions.joinToString(", ")})"
    }
    return listOfNotNull(
        table(
            "funds",
            "`fundId`",
            2 to "`fundId` TEXT NOT NULL",
            2 to "`name` TEXT NOT NULL",
            2 to "`currency` TEXT NOT NULL",
            3 to "`isin` TEXT",
            5 to "`fondlistaFundId` TEXT"
        ),
        table(
            "transactions", null,
            2 to "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL", 2 to "`fundId` TEXT NOT NULL",
            2 to "`type` TEXT NOT NULL", 2 to "`epochDay` INTEGER NOT NULL", 2 to "`shares` REAL NOT NULL",
            2 to "`pricePerShare` REAL NOT NULL", 4 to "`fee` REAL NOT NULL DEFAULT 0.0"
        ),
        "CREATE INDEX `index_transactions_fundId` ON `transactions` (`fundId`)",
        table(
            "fund_prices",
            "`fundId`, `epochDay`",
            2 to "`fundId` TEXT NOT NULL",
            2 to "`epochDay` INTEGER NOT NULL",
            2 to "`nav` REAL NOT NULL",
            2 to "`currency` TEXT NOT NULL"
        ),
        table(
            "fx_rates",
            "`currency`, `epochDay`",
            7 to "`currency` TEXT NOT NULL",
            7 to "`epochDay` INTEGER NOT NULL",
            7 to "`rate` REAL NOT NULL"
        ).takeIf { version >= 7 },
        table(
            "fund_metadata", "`isin`",
            8 to "`isin` TEXT NOT NULL", 8 to "`name` TEXT NOT NULL", 8 to "`orderbookId` TEXT NOT NULL",
            8 to "`totalFee` REAL", 8 to "`managementFee` REAL", 8 to "`category` TEXT", 8 to "`fundType` TEXT",
            8 to "`companyName` TEXT", 8 to "`risk` INTEGER", 8 to "`indexFund` INTEGER NOT NULL",
            8 to "`startDateEpochDay` INTEGER", 8 to "`minimumBuy` REAL", 8 to "`tagsJson` TEXT NOT NULL",
            8 to "`availableAtHandelsbanken` INTEGER", 8 to "`availabilityResolvedAtEpochDay` INTEGER",
            8 to "`fetchedAtEpochDay` INTEGER NOT NULL",
            9 to "`cheapestAlternativeIsin` TEXT", 9 to "`cheapestAlternativeFee` REAL",
            9 to "`comparisonResolvedAtEpochDay` INTEGER",
            10 to "`developmentOneYear` REAL",
            14 to "`shownAlternativeIsinsJson` TEXT NOT NULL DEFAULT '[]'"
        ).takeIf { version >= 8 },
        table(
            "suggestion_records", null,
            10 to "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL", 10 to "`suggestedAtEpochDay` INTEGER NOT NULL",
            10 to "`planIndex` INTEGER NOT NULL", 10 to "`sellIsin` TEXT NOT NULL", 10 to "`buyIsin` TEXT NOT NULL",
            10 to "`sellNavAtSuggestion` REAL NOT NULL", 10 to "`buyNavAtSuggestion` REAL NOT NULL",
            10 to "`followed` INTEGER",
            11 to "`switchValueKr` REAL",
            12 to "`batchEpochMillis` INTEGER NOT NULL DEFAULT 0",
            13 to "`kind` TEXT NOT NULL DEFAULT 'RISK_PLAN'"
        ).takeIf { version >= 10 }
    )
}

/**
 * Bygger en gammal databas [dbName] i version [version] med rå SQL: tar bort en kvarlämnad fil,
 * kör [schema] (som standard [legacySchemaStatements]) och sedan [seed] för raderna, och sätter
 * `user_version`. Raderna skrivs i varje test — det är dem testet handlar om.
 */
internal fun Context.createLegacyDatabase(
    dbName: String,
    version: Int,
    schema: List<String> = legacySchemaStatements(version),
    seed: (SQLiteDatabase) -> Unit
) {
    deleteDatabase(dbName)
    SQLiteDatabase.openOrCreateDatabase(getDatabasePath(dbName), null).use { db ->
        schema.forEach(db::execSQL)
        seed(db)
        db.version = version
    }
}

/** Öppnar [dbName] med appens alla migreringar och kör dem (som appen vid start). */
internal fun Context.openMigratedDatabase(dbName: String): AppDatabase = Room.databaseBuilder(this, AppDatabase::class.java, dbName)
    .addMigrations(*AppDatabase.MIGRATIONS)
    .build()
    .also { it.openHelper.writableDatabase }

/**
 * Grunddatan i testerna 7→8 till 9→10: fonden SHB0000442 med ett köp, en kurs och USD-kursen.
 * Kräver `fx_rates`, alltså version 7 eller senare. Kontrolleras med
 * [assertFundWithPriceAndFxRateUntouched].
 */
internal fun insertFundWithPriceAndFxRate(db: SQLiteDatabase) {
    db.execSQL(
        "INSERT INTO funds (fundId, name, currency, isin, fondlistaFundId) " +
            "VALUES ('SHB0000442', 'Handelsbanken Amerika Småbolag Tema', 'SEK', 'SE0000582033', NULL)"
    )
    db.execSQL(
        "INSERT INTO transactions (fundId, type, epochDay, shares, pricePerShare, fee) " +
            "VALUES ('SHB0000442', 'KOP', 20000, 2.0, 150.0, 0.0)"
    )
    db.execSQL("INSERT INTO fund_prices VALUES ('SHB0000442', 20658, 164.35, 'SEK')")
    db.execSQL("INSERT INTO fx_rates VALUES ('USD', 20658, 9.73973)")
}

/** Fonden, köpet, kursen och växelkursen från [insertFundWithPriceAndFxRate] finns kvar efter migreringen. */
internal suspend fun AppDatabase.assertFundWithPriceAndFxRateUntouched() {
    assertEquals("Handelsbanken Amerika Småbolag Tema", fundDao().getByFundId("SHB0000442")?.name)
    assertEquals(1, transactionDao().observeForFund("SHB0000442").first().size)
    assertTrue(
        fundPriceDao().getRange("SHB0000442", fromEpochDay = Long.MIN_VALUE, toEpochDay = Long.MAX_VALUE)
            .isNotEmpty()
    )
    assertEquals(9.73973, fxRateDao().getLatest("USD")?.rate ?: -1.0, 1e-9)
}
