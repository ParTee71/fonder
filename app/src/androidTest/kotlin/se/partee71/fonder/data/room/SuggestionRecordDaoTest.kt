package se.partee71.fonder.data.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.data.room.daos.SuggestionRecordDao
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity
import se.partee71.fonder.domain.model.SuggestionKind

/**
 * DAO-rundtur för [SuggestionRecordDao] — facit-inspelningen (HEM-8) och dess redovisning
 * (SET-5, issue #80). Samma mönster som [FundMetadataDaoTest].
 */
@RunWith(AndroidJUnit4::class)
class SuggestionRecordDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SuggestionRecordDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        dao = db.suggestionRecordDao()
    }

    @After
    fun tearDown() = db.close()

    private fun entity(
        suggestedAtEpochDay: Long = 20_000,
        planIndex: Int = 0,
        sellIsin: String = "SE_SELL",
        buyIsin: String = "SE_BUY",
        followed: Boolean? = null,
        batchEpochMillis: Long = 0,
        kind: SuggestionKind = SuggestionKind.RISK_PLAN,
    ) = SuggestionRecordEntity(
        suggestedAtEpochDay = suggestedAtEpochDay,
        planIndex = planIndex,
        sellIsin = sellIsin,
        buyIsin = buyIsin,
        sellNavAtSuggestion = 100.0,
        buyNavAtSuggestion = 200.0,
        switchValueKr = 10_000.0,
        followed = followed,
        batchEpochMillis = batchEpochMillis,
        kind = kind.name,
    )

    @Test
    fun `setFollowed skriver och lases tillbaka`() = runTest {
        val id = dao.insert(entity())

        assertNull(dao.getAll().single().followed)

        dao.setFollowed(id, true)
        assertEquals(true, dao.getAll().single().followed)

        // Går också att ångra — markeringen är användarens, inte en engångsflagga.
        dao.setFollowed(id, false)
        assertEquals(false, dao.getAll().single().followed)
    }

    @Test
    fun `setFollowed ror bara den angivna raden`() = runTest {
        val forsta = dao.insert(entity(planIndex = 0))
        dao.insert(entity(planIndex = 1))

        dao.setFollowed(forsta, true)

        val rows = dao.getAll().sortedBy { it.planIndex }
        assertEquals(true, rows[0].followed)
        assertNull(rows[1].followed)
    }

    @Test
    // Inget kommatecken i namnet: ett instrumenterat testnamn blir ett dex-metodnamn, och D8
    // vägrar representera "," (mellanslag och bindestreck går bra, se övriga testnamn här).
    fun `observeHistory ger alla batchar - nyast forst`() = runTest {
        // observeLatestBatch ger med flit bara den senaste körningen — facit ska se hela
        // historiken, annars kan utfallet aldrig utvärderas över tid (SET-5).
        dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 1_000, planIndex = 0))
        dao.insert(entity(suggestedAtEpochDay = 20_001, batchEpochMillis = 2_000, planIndex = 0))
        dao.insert(entity(suggestedAtEpochDay = 20_001, batchEpochMillis = 2_000, planIndex = 1))

        val history = dao.observeHistory().first()

        assertEquals(3, history.size)
        assertEquals(listOf(20_001L, 20_001L, 20_000L), history.map { it.suggestedAtEpochDay })
        assertEquals(listOf(0, 1, 0), history.map { it.planIndex })
    }

    @Test
    fun `observeHistory haller ihop en batch aven nar tva korningar landat samma dygn`() = runTest {
        // Backstopen kör var 12:e timme, så två planer kan ha samma dygn men olika körning.
        dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 1_000, planIndex = 0))
        dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 2_000, planIndex = 0))
        dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 1_000, planIndex = 1))

        val history = dao.observeHistory().first()

        assertEquals(listOf(2_000L, 1_000L, 1_000L), history.map { it.batchEpochMillis })
        assertEquals(listOf(0, 0, 1), history.map { it.planIndex })
    }

    @Test
    fun `observeLatestBatch paverkas inte av att historiken kan lasas`() = runTest {
        dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 1_000))
        dao.insert(entity(suggestedAtEpochDay = 20_001, batchEpochMillis = 2_000))

        val senaste = dao.observeLatestBatch().first()

        assertEquals(1, senaste.size)
        assertEquals(20_001L, senaste.single().suggestedAtEpochDay)
        assertTrue(dao.observeHistory().first().size == 2)
    }

    // --- Sorten på raden (issue #91) ---

    @Test
    fun `kind gar rundturen och defaultar till bytesplansbyte`() = runTest {
        dao.insert(entity(planIndex = 0))
        dao.insert(entity(planIndex = 1, kind = SuggestionKind.FEE))

        val rows = dao.getAll().sortedBy { it.planIndex }
        assertEquals(SuggestionKind.RISK_PLAN, rows[0].toDomain().kind)
        assertEquals(SuggestionKind.FEE, rows[1].toDomain().kind)
    }

    @Test
    fun `observeLatestBatch utelamnar avgiftsbyten fran samma korning`() = runTest {
        dao.insert(entity(suggestedAtEpochDay = 20_001, batchEpochMillis = 2_000, planIndex = 0))
        dao.insert(entity(suggestedAtEpochDay = 20_001, batchEpochMillis = 2_000, buyIsin = "SE_BILLIG", kind = SuggestionKind.FEE))

        val senaste = dao.observeLatestBatch().first()

        assertEquals(listOf("SE_BUY"), senaste.map { it.buyIsin })
    }

    @Test
    // Regression för issue #91: MAX()-underfrågorna måste filtrera på kind de också.
    fun `observeLatestBatch ger planens senaste batch aven nar ett senare dygn bara har avgiftsbyten`() = runTest {
        dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 1_000, planIndex = 0))
        // Dygnet efter gav planen ingenting — bara avgiftsskanningen spelade in något. Väljs
        // dygnet utan hänsyn till sorten blir planens batch tom och Hem visar ingen plan alls.
        dao.insert(entity(suggestedAtEpochDay = 20_001, batchEpochMillis = 2_000, buyIsin = "SE_BILLIG", kind = SuggestionKind.FEE))

        val senaste = dao.observeLatestBatch().first()

        assertEquals(1, senaste.size)
        assertEquals(20_000L, senaste.single().suggestedAtEpochDay)
        assertEquals("SE_BUY", senaste.single().buyIsin)
    }

    @Test
    fun `existsForDay skiljer pa sorterna for samma fondpar`() = runTest {
        dao.insert(entity(suggestedAtEpochDay = 20_000))

        assertTrue(dao.existsForDay("SE_SELL", "SE_BUY", 20_000, SuggestionKind.RISK_PLAN.name))
        // Samma fondpar samma dygn, men den andra sorten — spärren får inte tyst blockera den.
        assertFalse(dao.existsForDay("SE_SELL", "SE_BUY", 20_000, SuggestionKind.FEE.name))
        assertFalse(dao.existsForDay("SE_SELL", "SE_BUY", 20_001, SuggestionKind.RISK_PLAN.name))
    }

    @Test
    // ANA-9 på Beslut (NAV-9): bara sorten och fönstret, nyast först.
    fun `observeRecent ger sortens rader fran och med dagen nyast forst`() = runTest {
        dao.insert(entity(suggestedAtEpochDay = 19_990, buyIsin = "SE_GAMMAL", kind = SuggestionKind.FEE))
        dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 1_000, buyIsin = "SE_A", kind = SuggestionKind.FEE))
        dao.insert(entity(suggestedAtEpochDay = 20_001, batchEpochMillis = 2_000, buyIsin = "SE_B", kind = SuggestionKind.FEE))
        dao.insert(entity(suggestedAtEpochDay = 20_001, batchEpochMillis = 2_000, buyIsin = "SE_PLAN"))

        val recent = dao.observeRecent(SuggestionKind.FEE.name, sinceEpochDay = 20_000).first()

        assertEquals(listOf("SE_B", "SE_A"), recent.map { it.buyIsin })
    }

    // --- Issue #143: flytt mellan körningar och de riktade frågorna ---

    @Test
    fun `moveToBatch flyttar raden med id och kvittering men byter korning plats och belopp`() = runTest {
        val id = dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 1_000, planIndex = 1, followed = true))
        dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 1_000, planIndex = 0, buyIsin = "SE_ANNAN"))

        dao.moveToBatch(id, batchEpochMillis = 2_000, planIndex = 0, switchValueKr = 2_500.0)

        val latest = dao.observeLatestBatch().first().single()
        assertEquals(id, latest.id)
        assertEquals(0, latest.planIndex)
        assertEquals(2_500.0, latest.switchValueKr!!, 1e-9)
        assertEquals(true, latest.followed)
        assertEquals(100.0, latest.sellNavAtSuggestion, 1e-9)
        // Den andra raden ligger kvar i sin gamla körning — rådet gavs och mäts i facit.
        assertEquals(2, dao.getAll().size)
    }

    @Test
    fun `observeByIds ger bara de begarda raderna`() = runTest {
        val a = dao.insert(entity(buyIsin = "SE_A"))
        dao.insert(entity(buyIsin = "SE_B"))
        val c = dao.insert(entity(buyIsin = "SE_C"))

        assertEquals(setOf("SE_A", "SE_C"), dao.observeByIds(listOf(a, c)).first().map { it.buyIsin }.toSet())
    }

    @Test
    fun `observeLatestPlanRecordsFor ger senaste planraden per par i planordning utan avgiftsbyten`() = runTest {
        dao.insert(entity(suggestedAtEpochDay = 19_990, batchEpochMillis = 100, planIndex = 0, buyIsin = "SE_A"))
        val newestA = dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 2_000, planIndex = 1, buyIsin = "SE_A"))
        dao.insert(entity(suggestedAtEpochDay = 20_000, batchEpochMillis = 1_000, planIndex = 0, buyIsin = "SE_A"))
        val onlyB = dao.insert(entity(suggestedAtEpochDay = 19_995, planIndex = 0, buyIsin = "SE_B"))
        dao.insert(entity(suggestedAtEpochDay = 20_001, planIndex = 0, buyIsin = "SE_A", kind = SuggestionKind.FEE))
        dao.insert(entity(suggestedAtEpochDay = 20_001, planIndex = 0, buyIsin = "SE_UTANFOR"))

        val rows = dao.observeLatestPlanRecordsFor(listOf("SE_SELL"), listOf("SE_A", "SE_B")).first()

        assertEquals(listOf(onlyB, newestA), rows.map { it.id })
        assertEquals(listOf(0, 1), rows.map { it.planIndex })
    }
}
