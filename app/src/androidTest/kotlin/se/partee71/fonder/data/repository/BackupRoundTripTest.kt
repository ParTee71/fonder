package se.partee71.fonder.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.datastore.ThemeMode
import se.partee71.fonder.data.room.AppDatabase
import se.partee71.fonder.data.room.entities.FundEntity
import se.partee71.fonder.data.room.entities.FundPriceEntity
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity
import se.partee71.fonder.data.room.entities.SwitchWatchCandidateEntity
import se.partee71.fonder.data.room.entities.SwitchWatchEntity
import se.partee71.fonder.data.room.entities.TransactionEntity
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.RiskProfileAnswers
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SwitchWatchCandidateSource
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.model.TimeHorizon

/**
 * Rundturen som NFR-1 (regel 1) faktiskt kräver: **backup → tömning → restore** mot riktig Room
 * och riktig DataStore, inte mot fejkade lager. Tömningen däremellan är SET-1:s egen
 * "töm databasen" — hade den inte legat mitt i hade testet inte bevisat annat än att en
 * oförändrad databas är oförändrad.
 */
@RunWith(AndroidJUnit4::class)
class BackupRoundTripTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var preferences: PreferencesRepository
    private lateinit var backup: BackupRepository
    private lateinit var transactions: TransactionRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { tempFolder.newFile("backup_roundtrip.preferences_pb") },
        )
        preferences = PreferencesRepository(dataStore)
        backup = LocalBackupRepository(db, db.fundDao(), db.transactionDao(), db.suggestionRecordDao(), db.switchWatchDao(), preferences)
        transactions = RoomTransactionRepository(db, db.fundDao(), db.transactionDao(), db.fundPriceDao(), db.suggestionRecordDao(), db.switchWatchDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed() {
        db.fundDao().upsert(FundEntity(fundId = "SHB0000442", name = "Fond A", currency = "SEK", isin = "SE0000582033", fondlistaFundId = "SHB0000442"))
        db.fundDao().upsert(FundEntity(fundId = "LU0055631609", name = "Fond B", currency = "USD", isin = null, fondlistaFundId = null))
        db.transactionDao().insert(
            TransactionEntity(id = 11, fundId = "SHB0000442", type = "KOP", epochDay = 19_000, shares = 12.5, pricePerShare = 178.25, fee = 25.0),
        )
        db.transactionDao().insert(
            TransactionEntity(id = 12, fundId = "SHB0000442", type = "SALJ", epochDay = 19_500, shares = 4.0, pricePerShare = 201.5, fee = 0.0),
        )
        // followed i alla tre lägen — kolumnen är ett *val*, inte en mätning, och kan inte
        // härledas i efterhand: tappas den kan facit (SET-5) aldrig skilja ett följt råd från
        // ett bara givet.
        db.suggestionRecordDao().insert(
            SuggestionRecordEntity(
                id = 1, suggestedAtEpochDay = 19_800, planIndex = 0, sellIsin = "SE0000582033", buyIsin = "SE0001466368",
                sellNavAtSuggestion = 201.5, buyNavAtSuggestion = 95.25, switchValueKr = 4_200.0, followed = true,
                batchEpochMillis = 1_754_200_000_000,
            ),
        )
        db.suggestionRecordDao().insert(
            SuggestionRecordEntity(
                id = 2, suggestedAtEpochDay = 19_800, planIndex = 1, sellIsin = "SE0000582033", buyIsin = "SE0004617590",
                sellNavAtSuggestion = 201.5, buyNavAtSuggestion = 55.0, switchValueKr = null, followed = false,
                batchEpochMillis = 1_754_200_000_000,
            ),
        )
        db.suggestionRecordDao().insert(
            SuggestionRecordEntity(
                id = 3, suggestedAtEpochDay = 19_400, planIndex = 0, sellIsin = "SE0000582033", buyIsin = "SE0005991445",
                sellNavAtSuggestion = 190.0, buyNavAtSuggestion = 70.0, switchValueKr = 1_000.0, followed = null,
                batchEpochMillis = 0,
                // Ett avgiftsbyte (issue #91): sorten avgör vilken summering raden räknas i och
                // om Hem visar den — tappas den i rundturen byter rådet betydelse.
                kind = SuggestionKind.FEE.name,
            ),
        )
        // Ett öppet och ett avslutat byte (ANA-12, issue #114). Nollpunkten (navAtStart) kan
        // inte återskapas i efterhand — kandidaten ligger inte i kurscachen (ANA-11) — och det
        // avslutade bytet bär vad användaren faktiskt köpte.
        val watchId = db.switchWatchDao().insertWatch(
            SwitchWatchEntity(
                id = 1, sellIsin = "SE0000582033", sellFundName = "Fond A", soldAtEpochDay = 19_900,
                proceedsKr = 12_500.0, targetLevel = 4, sourceRecordId = 1,
                closedAtEpochDay = null, boughtIsin = null, closeReason = null,
            ),
        )
        db.switchWatchDao().insertCandidates(
            listOf(
                SwitchWatchCandidateEntity(
                    watchId = watchId, isin = "SE0001466368", name = "Kandidat A",
                    navAtStart = 95.25, navAtStartEpochDay = 19_900,
                    source = SwitchWatchCandidateSource.AUTO.name, position = 0,
                ),
                SwitchWatchCandidateEntity(
                    watchId = watchId, isin = "SE0004617590", name = "Kandidat B",
                    navAtStart = null, navAtStartEpochDay = null,
                    source = SwitchWatchCandidateSource.MANUELL.name, position = 1,
                ),
            ),
        )
        db.switchWatchDao().insertWatch(
            SwitchWatchEntity(
                id = 2, sellIsin = "SE0005991445", sellFundName = "Fond C", soldAtEpochDay = 19_500,
                proceedsKr = null, targetLevel = null, sourceRecordId = null,
                closedAtEpochDay = 19_505, boughtIsin = "SE0001466368",
                closeReason = SwitchWatchCloseReason.KOPT.name,
            ),
        )
        preferences.setRiskProfile(
            RiskProfile(
                targetAllocation = mapOf(3 to 0.25, 4 to 0.5, 5 to 0.25),
                answers = RiskProfileAnswers(TimeHorizon.SJU_TILL_15_AR, DownturnReaction.KOPER_MER, PrimaryGoal.MAXIMAL_TILLVAXT),
                targetRiskLevel = 4,
            ),
        )
        preferences.setAccountType(AccountType.ISK_KF)
        preferences.setThemeMode(ThemeMode.DARK)
        // Eget val av jämförelsefond (HEM-10, issue #102) — ett val, inte härledd cache, och
        // därför en del av kontraktet.
        preferences.setChosenBenchmarkIsin("SE0011527613")
    }

    @Test
    fun backup_tomning_restore_ger_tillbaka_all_anvandardata() = runTest {
        seed()

        val json = backup.export().getOrThrow()

        transactions.clearAll()
        assertTrue(db.fundDao().getAll().isEmpty())
        assertTrue(db.transactionDao().getAll().isEmpty())
        assertTrue(db.suggestionRecordDao().getAll().isEmpty())
        assertTrue(db.switchWatchDao().getAll().isEmpty())

        val summary = backup.restore(json).getOrThrow()

        assertEquals(
            RestoreSummary(funds = 2, transactions = 2, suggestionRecords = 3, switchWatches = 2),
            summary,
        )

        val funds = db.fundDao().getAll().sortedBy { it.fundId }
        assertEquals(listOf("LU0055631609", "SHB0000442"), funds.map { it.fundId })
        assertEquals("SE0000582033", funds[1].isin)
        assertEquals("SHB0000442", funds[1].fondlistaFundId)
        assertNull(funds[0].isin)
        assertEquals("USD", funds[0].currency)

        val txs = db.transactionDao().getAll().sortedBy { it.id }
        assertEquals(listOf(11L, 12L), txs.map { it.id })
        assertEquals(listOf("KOP", "SALJ"), txs.map { it.type })
        assertEquals(25.0, txs[0].fee, 1e-9)
        assertEquals(12.5, txs[0].shares, 1e-9)
        assertEquals(178.25, txs[0].pricePerShare, 1e-9)

        val records = db.suggestionRecordDao().getAll().sortedBy { it.id }
        assertEquals(listOf(true, false, null), records.map { it.followed })
        assertEquals(listOf(4_200.0, null, 1_000.0), records.map { it.switchValueKr })
        assertEquals(listOf(1_754_200_000_000L, 1_754_200_000_000L, 0L), records.map { it.batchEpochMillis })
        assertEquals(
            listOf(SuggestionKind.RISK_PLAN, SuggestionKind.RISK_PLAN, SuggestionKind.FEE),
            records.map { it.toDomain().kind },
        )
        assertEquals(listOf(0, 1, 0), records.map { it.planIndex })
        assertEquals(201.5, records[0].sellNavAtSuggestion, 1e-9)
        assertEquals(95.25, records[0].buyNavAtSuggestion, 1e-9)

        val watches = db.switchWatchDao().getAll().map { it.toDomain() }.sortedBy { it.id }
        assertEquals(listOf("SE0000582033", "SE0005991445"), watches.map { it.sellIsin })
        val open = watches.first()
        assertEquals("Fond A", open.sellFundName)
        assertEquals(19_900L, open.soldAtEpochDay)
        assertEquals(12_500.0, open.proceedsKr!!, 1e-9)
        assertEquals(4, open.targetLevel)
        assertEquals(1L, open.sourceRecordId)
        assertTrue(open.isOpen)
        // Ordningen, källan och nollpunkten måste alla överleva: utan nollpunkten går
        // utvecklingen sedan säljdagen aldrig att visa igen.
        assertEquals(listOf("SE0001466368", "SE0004617590"), open.candidates.map { it.isin })
        assertEquals(listOf(95.25, null), open.candidates.map { it.navAtStart })
        assertEquals(listOf(19_900L, null), open.candidates.map { it.navAtStartEpochDay })
        assertEquals(
            listOf(SwitchWatchCandidateSource.AUTO, SwitchWatchCandidateSource.MANUELL),
            open.candidates.map { it.source },
        )

        val closed = watches.last()
        assertFalse(closed.isOpen)
        assertEquals("SE0001466368", closed.boughtIsin)
        assertEquals(SwitchWatchCloseReason.KOPT, closed.closeReason)
        assertNull(closed.proceedsKr)

        val profile = preferences.riskProfile.first()
        assertEquals(mapOf(3 to 0.25, 4 to 0.5, 5 to 0.25), profile?.targetAllocation)
        assertEquals(4, profile?.targetRiskLevel)
        assertEquals(TimeHorizon.SJU_TILL_15_AR, profile?.answers?.horizon)
        assertEquals(AccountType.ISK_KF, preferences.accountType.first())
        assertEquals(ThemeMode.DARK, preferences.themeMode.first())
        assertEquals("SE0011527613", preferences.chosenBenchmarkIsin.first())
    }

    @Test
    fun en_fil_utan_eget_referensval_rensar_ett_befintligt() = runTest {
        // Återställning **ersätter**, den slår inte ihop (SET-6): ett val som inte fanns när
        // filen skrevs får inte överleva den.
        seed()
        preferences.clearChosenBenchmarkIsin()
        val utanVal = backup.export().getOrThrow()
        preferences.setChosenBenchmarkIsin("SE0099999999")

        backup.restore(utanVal).getOrThrow()

        assertNull(preferences.chosenBenchmarkIsin.first())
    }

    @Test
    fun restore_ersatter_befintlig_data_i_stallet_for_att_slas_ihop() = runTest {
        seed()
        val json = backup.export().getOrThrow()

        // Samma fil återställd igen, utan tömning däremellan: en sammanslagning hade dubblerat
        // varje transaktion. Det här är en återställning, inte en import (IMP-1/IMP-6).
        backup.restore(json).getOrThrow()

        assertEquals(2, db.fundDao().getAll().size)
        assertEquals(2, db.transactionDao().getAll().size)
        assertEquals(3, db.suggestionRecordDao().getAll().size)
    }

    @Test
    fun en_trasig_fil_lamnar_databasen_orord() = runTest {
        seed()

        val error = backup.restore("inte en sakerhetskopia").exceptionOrNull()

        assertEquals(BackupFormatException.Reason.UNREADABLE, (error as BackupFormatException).reason)
        assertEquals(2, db.fundDao().getAll().size)
        assertEquals(2, db.transactionDao().getAll().size)
        assertEquals(3, db.suggestionRecordDao().getAll().size)
        assertEquals(AccountType.ISK_KF, preferences.accountType.first())
    }

    @Test
    fun cachade_kurser_ingar_inte_i_filen_och_roras_inte_av_en_aterstallning() = runTest {
        seed()
        db.fundPriceDao().upsertAll(
            listOf(FundPriceEntity(fundId = "SHB0000442", epochDay = 19_500, nav = 201.5, currency = "SEK")),
        )
        val json = backup.export().getOrThrow()

        backup.restore(json).getOrThrow()

        // Kurscachen är härledd data som hämtas om — den ingår inte i filen och töms inte av en
        // återställning, så den återställda fonden behåller den historik som redan finns lokalt.
        assertEquals(201.5, db.fundPriceDao().getLatest("SHB0000442")!!.nav, 1e-9)
    }
}
