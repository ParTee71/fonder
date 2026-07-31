package se.partee71.fonder.data.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import se.partee71.fonder.data.room.daos.FundDao
import se.partee71.fonder.data.room.daos.FundMetadataDao
import se.partee71.fonder.data.room.daos.FundPriceDao
import se.partee71.fonder.data.room.daos.FxRateDao
import se.partee71.fonder.data.room.daos.TransactionDao
import se.partee71.fonder.data.room.entities.FundEntity
import se.partee71.fonder.data.room.entities.FundMetadataEntity
import se.partee71.fonder.data.room.entities.FundPriceEntity
import se.partee71.fonder.data.room.entities.FxRateEntity
import se.partee71.fonder.data.room.entities.TransactionEntity

@Database(
    entities = [
        FundEntity::class,
        TransactionEntity::class,
        FundPriceEntity::class,
        FxRateEntity::class,
        FundMetadataEntity::class,
    ],
    version = 8,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fundDao(): FundDao
    abstract fun transactionDao(): TransactionDao
    abstract fun fundPriceDao(): FundPriceDao
    abstract fun fxRateDao(): FxRateDao
    abstract fun fundMetadataDao(): FundMetadataDao

    companion object {
        const val NAME = "fonder.db"

        /**
         * Version 1 → 2: byter namn på `isin`/`fundIsin` → `fundId` (spike-issue #2 visade
         * att källan inte har ISIN) och lägger till `fund_prices` för cachad kurshistorik
         * (issue #3). RENAME COLUMN kräver SQLite 3.25+, vilket Android garanterar från
         * minSdk 30. Körs även om ingen enhet någonsin haft version 1 med riktig data.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `funds` RENAME COLUMN `isin` TO `fundId`")
                db.execSQL("ALTER TABLE `transactions` RENAME COLUMN `fundIsin` TO `fundId`")
                // Indexnamnet följer inte med kolumnbytet — byt ut det så det matchar Rooms
                // förväntade schema för version 2 (index_transactions_fundId).
                db.execSQL("DROP INDEX IF EXISTS `index_transactions_fundIsin`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transactions_fundId` ON `transactions` (`fundId`)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fund_prices` (
                        `fundId` TEXT NOT NULL,
                        `epochDay` INTEGER NOT NULL,
                        `nav` REAL NOT NULL,
                        `currency` TEXT NOT NULL,
                        PRIMARY KEY(`fundId`, `epochDay`)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Version 2 → 3: lägger till `isin` (nullable) på `funds` — nytt attribut för att
         * hämta full kurshistorik sedan köpdatum från ISIN-baserade källor (Avanza m.fl.,
         * se KRAVLISTA TP-14), utöver Handelsbankens FundId-baserade källa. Nullable eftersom
         * fonder tillagda via fondsök saknar ISIN tills det bekräftats i Fonddetalj.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `funds` ADD COLUMN `isin` TEXT")
            }
        }

        /**
         * Version 3 → 4: lägger till `fee` (avgift, NOT NULL DEFAULT 0.0) på `transactions`
         * — nytt attribut för realisationsberäkning av sälj-transaktioner (FIFO, se
         * `RealizedGainCalculator`, issue #10). Befintliga rader får 0.0, dvs. ingen känd
         * avgift — oförändrat beteende för all historisk data.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `transactions` ADD COLUMN `fee` REAL NOT NULL DEFAULT 0.0")
            }
        }

        /**
         * Version 4 → 5 (issue #39), två delar:
         *
         * 1. Lägger till `fondlistaFundId` (nullable) på `funds` — fondlista-plattformens kod
         *    för en fond vars `fundId` är ett ISIN (importmatchad via `findFundByIsin`,
         *    TP-13/TP-14). Utan den nåddes de fonderna aldrig av fondlista-källan och fastnade
         *    på Avanzas eftersläpande data. Fondens identitet ändras **inte** — se
         *    [se.partee71.fonder.domain.model.Fund.fondlistaFundId].
         *
         * 2. Rensar **helgdaterade** rader ur `fund_prices`. Avanza levererar punkter daterade
         *    på lördag/söndag (verifierat 2026-07-27: en fonds serie hoppade över fredagen och
         *    gav en söndag i stället). En sådan kurs finns inte, och eftersom datumet är
         *    *nyare* än senaste handelsdag gjorde den `FundPriceRepository.isPriceStale`
         *    (TP-17) falskt negativ — fonden ansågs färsk och slutade uppdateras. Nya punkter
         *    filtreras bort i `AvanzaJsonParser`, men redan cachade rader måste bort här,
         *    annars fortsätter de blockera uppdateringen.
         *
         * Epoch-dag 0 är torsdag 1970-01-01, så `(epochDay + 3) % 7` ger 0=måndag … 6=söndag.
         * Kurser är härledd cache-data (NFR-1) och hämtas om vid nästa uppdatering.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `funds` ADD COLUMN `fondlistaFundId` TEXT")
                db.execSQL("DELETE FROM `fund_prices` WHERE ((`epochDay` % 7) + 10) % 7 >= 5")
            }
        }

        /**
         * Version 5 → 6 (issue #41): rensar kurser som **inte är i kronor** ur `fund_prices`.
         *
         * Fondlista noterar varje fond i fondens egen valuta. När den blev källa även för
         * ISIN-matchade fonder (#39/#40) hamnade USD-noterade NAV i cachen, där hela
         * värdekedjan räknar kronor utan konvertering — CPR Invest Global Gold Mines föll från
         * 14 462 kr till 1 490 kr (−87 %) enbart för att 1878,75 SEK ersattes av 193,48 USD.
         * Diagrammet blandade dessutom bägge, med kvarvarande SEK-rader som spikar.
         *
         * Rensar också rader som Avanza-källan tidigare märkte med fondens egen valuta trots
         * att värdena var i kronor — de var rätt värden men fel märkta, och kan inte skiljas
         * från de felaktiga här. Kurser är härledd cache-data (NFR-1): allt hämtas om vid
         * nästa uppdatering, nu med korrekt valuta.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DELETE FROM `fund_prices` WHERE `currency` <> 'SEK'")
            }
        }

        /**
         * Version 6 → 7 (issue #43): lägger till `fx_rates` för Riksbankens dagsnoterade
         * växelkurser (TP-20), så fondlistas kurser i fondens egen valuta kan räknas om till
         * kronor i stället för att kastas (TP-19, issue #41).
         *
         * Tömmer samtidigt kurscachen. Fonder som hittills betjänats av Avanza får nu sina
         * kurser från fondlista, och de två källorna skiljer sig någon promille (Avanza
         * använder sin egen växelkurstidpunkt — 1878,75 mot 1884,44 för CPR 2026-07-23). Utan
         * tömning hade serien fått en konstlad nivåskillnad just där källan byter, vilket
         * `PortfolioPerformanceCalc` skulle läsa som en verklig dagsrörelse. Kurser är härledd
         * data (NFR-1) och hämtas om vid nästa uppdatering; fonder, transaktioner och avgifter
         * rörs inte.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fx_rates` (
                        `currency` TEXT NOT NULL,
                        `epochDay` INTEGER NOT NULL,
                        `rate` REAL NOT NULL,
                        PRIMARY KEY(`currency`, `epochDay`)
                    )
                    """.trimIndent(),
                )
                db.execSQL("DELETE FROM `fund_prices`")
            }
        }

        /**
         * Version 7 → 8 (issue #57): lägger till `fund_metadata` för Avanzas sökbara
         * fondmetadata (avgift, kategori, fondtyp, risk, se KRAVLISTA TP-21) — grunden en
         * framtida köpscreener/rebalanseringsmotor byggs på. Helt ny tabell, cachad härledd
         * data precis som `fx_rates` (TP-20): går den förlorad hämtas den bara om vid nästa
         * fråga, och den ingår därför medvetet inte i backup-kontraktet (NFR-1). Fonder,
         * transaktioner och kurser rörs inte.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `fund_metadata` (
                        `isin` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `orderbookId` TEXT NOT NULL,
                        `totalFee` REAL,
                        `managementFee` REAL,
                        `category` TEXT,
                        `fundType` TEXT,
                        `companyName` TEXT,
                        `risk` INTEGER,
                        `indexFund` INTEGER NOT NULL,
                        `startDateEpochDay` INTEGER,
                        `minimumBuy` REAL,
                        `tagsJson` TEXT NOT NULL,
                        `availableAtHandelsbanken` INTEGER,
                        `availabilityResolvedAtEpochDay` INTEGER,
                        `fetchedAtEpochDay` INTEGER NOT NULL,
                        PRIMARY KEY(`isin`)
                    )
                    """.trimIndent(),
                )
            }
        }

        val MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
        )
    }
}
