package se.partee71.fonder.data.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import se.partee71.fonder.data.room.daos.FundDao
import se.partee71.fonder.data.room.daos.FundMetadataDao
import se.partee71.fonder.data.room.daos.FundPriceDao
import se.partee71.fonder.data.room.daos.FxRateDao
import se.partee71.fonder.data.room.daos.SuggestionRecordDao
import se.partee71.fonder.data.room.daos.SwitchWatchDao
import se.partee71.fonder.data.room.daos.TransactionDao
import se.partee71.fonder.data.room.entities.FundEntity
import se.partee71.fonder.data.room.entities.FundMetadataEntity
import se.partee71.fonder.data.room.entities.FundPriceEntity
import se.partee71.fonder.data.room.entities.FxRateEntity
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity
import se.partee71.fonder.data.room.entities.SwitchWatchCandidateEntity
import se.partee71.fonder.data.room.entities.SwitchWatchEntity
import se.partee71.fonder.data.room.entities.TransactionEntity

@Database(
    entities = [
        FundEntity::class,
        TransactionEntity::class,
        FundPriceEntity::class,
        FxRateEntity::class,
        FundMetadataEntity::class,
        SuggestionRecordEntity::class,
        SwitchWatchEntity::class,
        SwitchWatchCandidateEntity::class,
    ],
    version = 15,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun fundDao(): FundDao
    abstract fun transactionDao(): TransactionDao
    abstract fun fundPriceDao(): FundPriceDao
    abstract fun fxRateDao(): FxRateDao
    abstract fun fundMetadataDao(): FundMetadataDao
    abstract fun suggestionRecordDao(): SuggestionRecordDao
    abstract fun switchWatchDao(): SwitchWatchDao

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

        /**
         * Version 8 → 9 (issue #61): lägger till tre kolumner på `fund_metadata` för den
         * persisterade jämförelsen (ANA-9/HEM-6) — billigaste verifierat köpbara alternativets
         * ISIN och avgift, plus datumet jämförelsen gjordes. `comparisonResolvedAtEpochDay`
         * null = aldrig jämfört; satt datum med `cheapestAlternativeIsin` null = jämfört, inget
         * billigare hittades. Kronbesparingen sparas medvetet **inte** — den räknas alltid ur
         * innehavets aktuella värde vid visning, annars blir den fel så fort NAV rör sig.
         * Härledd cache-data precis som resten av `fund_metadata` (NFR-1); befintliga rader
         * (inklusive redan uppslagen köpbarhet) rörs inte, bara utökas med nullbara kolumner.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `fund_metadata` ADD COLUMN `cheapestAlternativeIsin` TEXT")
                db.execSQL("ALTER TABLE `fund_metadata` ADD COLUMN `cheapestAlternativeFee` REAL")
                db.execSQL("ALTER TABLE `fund_metadata` ADD COLUMN `comparisonResolvedAtEpochDay` INTEGER")
            }
        }

        /**
         * Version 9 → 10 (issue #70), två delar:
         *
         * 1. Lägger till `developmentOneYear` (nullable) på `fund_metadata` — källans
         *    12-månadersavkastning, enda undantaget från principen att inte cacha källans
         *    avkastningsmått (se [se.partee71.fonder.domain.model.FundMetadata]s KDoc):
         *    behövs för att rangordna köpkandidater bytesplanen (HEM-8) aldrig själv har hållit
         *    NAV-historik för. Befintliga rader (inklusive #61:s jämförelsefält) rörs inte.
         *
         * 2. Ny tabell `suggestion_records` — facit-inspelningen för varje föreslaget byte
         *    (datum, plats i planen, sälj-/köp-ISIN, NAV-utgångsläge). Till skillnad från
         *    `fund_metadata` är det här **genuin användardata** (samma kategori som
         *    `RiskProfile`, NFR-1): förslagstidpunkten kan inte återskapas ur NAV-historiken i
         *    efterhand om den går förlorad.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `fund_metadata` ADD COLUMN `developmentOneYear` REAL")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `suggestion_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `suggestedAtEpochDay` INTEGER NOT NULL,
                        `planIndex` INTEGER NOT NULL,
                        `sellIsin` TEXT NOT NULL,
                        `buyIsin` TEXT NOT NULL,
                        `sellNavAtSuggestion` REAL NOT NULL,
                        `buyNavAtSuggestion` REAL NOT NULL,
                        `followed` INTEGER
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * Version 10 → 11 (issue #75, punkt 1): `switchValueKr` (nullable) på
         * `suggestion_records` — beloppet ett bytesförslag avser. Bytet storleksbestäms numera
         * till gapet i stället för till hela positionen
         * ([se.partee71.fonder.domain.usecase.SwitchPlanCalc]), så beloppet är en del av rådet
         * och måste både sparas och visas.
         *
         * Nullable, inte `NOT NULL DEFAULT 0`: en rad inspelad före den här versionen avsåg
         * hela positionen med ett belopp som aldrig sparades, och 0 kr hade varit ett påhittat
         * värde som inte gick att skilja från ett verkligt (samma princip som att aldrig gissa
         * en okänd avgift, HEM-5). Befintliga rader rörs i övrigt inte.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `suggestion_records` ADD COLUMN `switchValueKr` REAL")
            }
        }

        /**
         * Version 11 → 12 (issue #75, fynd B): `batchEpochMillis` (NOT NULL DEFAULT 0) på
         * `suggestion_records` — vilken **körning** raden hör till.
         *
         * Hem visade "den senast inspelade planen" som *alla rader från det senaste dygnet*,
         * men backstopen kör var 12:e timme, så två körningar landar normalt samma dygn. Ändras
         * portföljen däremellan räknas en annan plan fram, och dygnsdedupen (`existsForDay`)
         * spärrar bara identiska sälj-/köp-par — resultatet blev en visad "plan" som var två
         * planer sammanslagna: samma fond kunde säljas två gånger och två rader bära
         * `planIndex 0`. Med ett körnings-id kan den senaste batchen läsas för sig.
         *
         * `DEFAULT 0` för befintliga rader: de saknar körnings-id, och att gruppera dem per
         * dygn (som förut) är den enda tolkning som finns — ingen data går förlorad, och
         * historiska rader beter sig exakt som tidigare. Inspelade förslag är genuin
         * användardata (NFR-1); tabellen utökas bara.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `suggestion_records` ADD COLUMN `batchEpochMillis` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Version 12 → 13: `suggestion_records.kind` skiljer bytesplanens byten (HEM-8) från
         * avgiftsbytena (ANA-9, issue #91). Befintliga rader **är** riskplansbyten, så defaulten
         * i SQL är det värdet — ingen datamigrering behövs, bara kolumnen.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `suggestion_records` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'RISK_PLAN'")
            }
        }

        /**
         * Version 13 → 14: `fund_metadata.shownAlternativeIsinsJson` — samtliga alternativ
         * ANA-9 visade, inte bara det billigaste (issue #93), så facit kan spela in varje
         * *givet* råd. `'[]'` för befintliga rader: de vet bara vilket alternativ som var
         * billigast, och att gissa resten vore att hitta på råd som aldrig gavs. Nästa
         * jämförelse fyller listan.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `fund_metadata` ADD COLUMN `shownAlternativeIsinsJson` TEXT NOT NULL DEFAULT '[]'")
            }
        }

        /**
         * Version 14 → 15 (issue #114): `switch_watches` + `switch_watch_candidates` — det
         * pågående bytet mellan sälj och köp (ANA-12/ANA-13).
         *
         * Två helt nya tabeller, inga befintliga rader rörs. Till skillnad från `fund_metadata`
         * är innehållet **genuin användardata** (samma kategori som `suggestion_records`,
         * NFR-1): säljdagen, beloppet och uppsättningen bevakade alternativ går inte att räkna
         * fram ur NAV-historiken i efterhand.
         *
         * Kandidaternas främmande nyckel är `ON DELETE CASCADE` med index på `watchId` —
         * indexet är inte valfritt, Rooms schemaverifiering kräver att det finns för
         * FK-kolumnen och migreringen hade annars fallit på nästa uppstart.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `switch_watches` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `sellIsin` TEXT NOT NULL,
                        `sellFundName` TEXT NOT NULL,
                        `soldAtEpochDay` INTEGER NOT NULL,
                        `proceedsKr` REAL,
                        `targetLevel` INTEGER,
                        `sourceRecordId` INTEGER,
                        `closedAtEpochDay` INTEGER,
                        `boughtIsin` TEXT,
                        `closeReason` TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `switch_watch_candidates` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `watchId` INTEGER NOT NULL,
                        `isin` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `navAtStart` REAL,
                        `navAtStartEpochDay` INTEGER,
                        `source` TEXT NOT NULL DEFAULT 'AUTO',
                        `position` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`watchId`) REFERENCES `switch_watches`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_switch_watch_candidates_watchId` ON `switch_watch_candidates` (`watchId`)",
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
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
            MIGRATION_13_14,
            MIGRATION_14_15,
        )
    }
}
