package se.partee71.fonder.domain.usecase

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.data.repository.FakeTransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import java.time.LocalDate

/**
 * Den enda skrivvägen för transaktioner (BYT-2, issue #143): en sparad transaktion rör inget
 * byte i A (väljaren och omräkningen kommer i #144), och bytets livscykel drivs av de vägar som
 * finns: "Bevaka alternativ" i Händelser ([RecordTransaction.startWatchForSale]) och ur ett
 * förslag ([RecordTransaction.startWatchFromSuggestion]), "Köpte den här"
 * ([RecordTransaction.markBought]). En bevakning per säljtransaktion oavsett startväg (BYT-1).
 */
class RecordTransactionTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 12)

    private val transactions = FakeTransactionRepository()
    private val watches = FakeSwitchWatchRepository()
    private val records = FakeSuggestionRecordRepository()
    private val metadata = FakeFundMetadataRepository()

    private val useCase = RecordTransaction(transactions, watches, records, metadata)

    private val sellFund = Fund(fundId = "SHB1", name = "Såld fond", isin = "SE_SALJ")
    private val buyFund = Fund(fundId = "SE_KOP", name = "Köpt fond", isin = "SE_KOP")

    private fun meta(isin: String, risk: Int?) = FundMetadata(
        isin = isin, name = "Fond $isin", orderbookId = isin, totalFee = 0.4, managementFee = 0.4,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
    )

    private fun sale(fundId: String = sellFund.fundId, day: Long = today.toEpochDay(), shares: Double = 10.0, price: Double = 125.0, fee: Double = 25.0) =
        Transaction(fundId = fundId, type = TransactionType.SALJ, epochDay = day, shares = shares, pricePerShare = price, fee = fee)

    private fun purchase(fundId: String = buyFund.fundId, day: Long = today.toEpochDay()) =
        Transaction(fundId = fundId, type = TransactionType.KOP, epochDay = day, shares = 20.0, pricePerShare = 60.0)

    private fun planRecord(id: Long = 7, buyIsin: String = "SE_KOP") = SuggestionRecord(
        id = id, suggestedAtEpochDay = today.minusDays(1).toEpochDay(), planIndex = 0,
        sellIsin = "SE_SALJ", buyIsin = buyIsin, sellNavAtSuggestion = 120.0, buyNavAtSuggestion = 60.0, switchValueKr = 4_200.0,
    )

    private suspend fun setUpPlan() {
        transactions.upsertFund(sellFund)
        transactions.upsertFund(buyFund)
        records.record(planRecord())
        metadata.metadataByIsin = mapOf("SE_SALJ" to meta("SE_SALJ", risk = 5), "SE_KOP" to meta("SE_KOP", risk = 3))
    }

    /** Sparar säljet och returnerar det med sitt id — som Händelser ser det. */
    private suspend fun savedSale(sale: Transaction = sale()): Transaction = sale.copy(id = useCase.record(sale))

    // --- Transaktionen skrivs, inget byte rörs ---

    @Test
    fun `record skriver transaktionen och ror ingen bevakning`() = runTest {
        // Formulär och import har ingen väljare i A (#144). En bevakning användaren startat ur ett
        // förslag samma dag är inte säljets förrän användaren säger det — inget gissas.
        setUpPlan()
        watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.toEpochDay(), sourceRecordId = 7))

        val id = useCase.record(sale())

        assertEquals(id, transactions.transactions.value.single().id)
        val untouched = watches.watches.value.single()
        assertNull(untouched.sellTransactionId)
        assertNull(untouched.proceedsKr)
        assertTrue(untouched.isOpen)
    }

    @Test
    fun `recordAll skriver raderna i given ordning`() = runTest {
        setUpPlan()

        val ids = useCase.recordAll(
            listOf(
                sale(day = today.minusDays(5).toEpochDay()),
                purchase(day = today.minusDays(4).toEpochDay()),
                purchase(day = today.minusDays(3).toEpochDay()),
            ),
        )

        assertEquals(ids, transactions.transactions.value.map { it.id })
        assertEquals(listOf(TransactionType.SALJ, TransactionType.KOP, TransactionType.KOP), transactions.transactions.value.map { it.type })
        assertTrue(watches.watches.value.isEmpty())
    }

    @Test
    fun `en tom import skriver ingenting`() = runTest {
        assertTrue(useCase.recordAll(emptyList()).isEmpty())
        assertTrue(transactions.transactions.value.isEmpty())
    }

    @Test
    fun `delete tar bort transaktionen och lamnar bevakningen kvar`() = runTest {
        setUpPlan()
        val sale = savedSale()
        useCase.startWatchForSale(sale, today)

        useCase.delete(sale.id)

        assertTrue(transactions.transactions.value.isEmpty())
        assertEquals(1, watches.watches.value.size)
    }

    // --- "Bevaka alternativ" i Händelser startar bevakningen ur säljet ---

    @Test
    fun `saljet bevakas med verklig dag likvid saljfondens egen niva och koppling`() = runTest {
        setUpPlan()
        val soldAt = today.minusDays(2).toEpochDay()
        val sale = savedSale(sale(day = soldAt))

        val watchId = useCase.startWatchForSale(sale, today)

        val watch = watches.watches.value.single()
        assertEquals(watch.id, watchId)
        assertEquals("SE_SALJ", watch.sellIsin)
        assertEquals("Såld fond", watch.sellFundName)
        // Säljdagen ur transaktionen, inte dagens datum — och likviden är belopp minus avgift.
        assertEquals(soldAt, watch.soldAtEpochDay)
        assertEquals(10.0 * 125.0 - 25.0, watch.proceedsKr!!, 1e-9)
        // Målnivån är säljfondens egen risknivå ur cachen; ingen planrad.
        assertEquals(5, watch.targetLevel)
        assertNull(watch.sourceRecordId)
        assertEquals(sale.id, watch.sellTransactionId)
    }

    @Test
    fun `utan cachad riskniva startas bevakningen utan malniva`() = runTest {
        setUpPlan()
        metadata.metadataByIsin = emptyMap()

        useCase.startWatchForSale(savedSale(), today)

        assertNull(watches.watches.value.single().targetLevel)
    }

    @Test
    fun `salj av en fond utan ISIN eller ett osparat salj eller ett kop bevakas inte`() = runTest {
        setUpPlan()
        transactions.upsertFund(Fund(fundId = "SHB9", name = "Utan ISIN", isin = null))

        assertNull(useCase.startWatchForSale(savedSale(sale(fundId = "SHB9")), today))
        assertNull(useCase.startWatchForSale(sale(), today))
        assertNull(useCase.startWatchForSale(savedSale(purchase()), today))
        assertTrue(watches.watches.value.isEmpty())
    }

    @Test
    fun `en bevakning per saljtransaktion vid dubbelanrop`() = runTest {
        setUpPlan()
        val sale = savedSale()

        val first = useCase.startWatchForSale(sale, today)
        val again = useCase.startWatchForSale(sale, today)

        assertNull(again)
        assertEquals(first, watches.watches.value.single().id)
    }

    @Test
    fun `tva salj samma dag i samma fond far varsin bevakning`() = runTest {
        // Dedupen går på transaktionen — reserven (fond, dag) gäller bara bevakningar utan känd
        // transaktion. Annars dolde inte knappen sig för det andra säljet, men gjorde ingenting.
        setUpPlan()
        val first = savedSale(sale(shares = 4.0))
        val second = savedSale(sale(shares = 6.0))

        useCase.startWatchForSale(first, today)
        useCase.startWatchForSale(second, today)

        assertEquals(listOf(first.id, second.id), watches.watches.value.map { it.sellTransactionId })
        assertEquals(listOf(4.0 * 125.0 - 25.0, 6.0 * 125.0 - 25.0), watches.watches.value.map { it.proceedsKr })
    }

    @Test
    fun `en okopplad bevakning for samma fond och dag kopplas till saljet i stallet for en ny`() = runTest {
        // "Bevaka alternativ" på Beslut startade bevakningen (ur förslaget, utan sälj) — när säljet
        // bevakas från Händelser är det samma byte: koppla, ta likviden ur transaktionen, starta
        // ingen andra. Samma dag: säljdagen står och kandidaternas nollpunkt rörs inte.
        setUpPlan()
        val existing = watches.start(
            SwitchWatch(
                sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.toEpochDay(), proceedsKr = 4_200.0, targetLevel = 3, sourceRecordId = 7,
                candidates = listOf(SwitchWatchCandidate(isin = "SE_KOP", name = "Köpt fond", navAtStart = 60.0, navAtStartEpochDay = today.toEpochDay())),
            ),
        )
        val sale = savedSale()

        assertEquals(existing, useCase.startWatchForSale(sale, today))

        val watch = watches.watches.value.single()
        assertEquals(sale.id, watch.sellTransactionId)
        assertEquals(10.0 * 125.0 - 25.0, watch.proceedsKr!!, 1e-9)
        assertEquals(7L, watch.sourceRecordId)
        assertEquals(today.toEpochDay(), watch.soldAtEpochDay)
        assertEquals(60.0, watch.candidates.single().navAtStart!!, 1e-9)
        // Nästa sälj samma dag får en egen — den kopplade matchar bara på sin transaktion.
        useCase.startWatchForSale(savedSale(sale(shares = 2.0)), today)
        assertEquals(2, watches.watches.value.size)
    }

    @Test
    fun `en bevakning fran en annan dag kopplas inte - saljet far en egen`() = runTest {
        // Att flytta säljdagen och ankra om kandidaterna är en fråga till användaren (#144).
        setUpPlan()
        val fromToday = watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.toEpochDay()))
        val soldAt = today.minusDays(3).toEpochDay()

        val started = useCase.startWatchForSale(savedSale(sale(day = soldAt)), today)

        assertTrue(started != null && started != fromToday)
        assertNull(watches.watches.value.first { it.id == fromToday }.sellTransactionId)
        assertEquals(soldAt, watches.watches.value.first { it.id == started }.soldAtEpochDay)
    }

    @Test
    fun `en utgangen men inte stangd okopplad bevakning samma dag blockerar som forut`() = runTest {
        // Bakgrundskörningen har inte hunnit stänga den; den kan inte kopplas (utgången) men är
        // ändå säljets — en ny bredvid hade gett två bevakningar för samma sälj (fond + dag).
        setUpPlan()
        val expiredDay = today.minusDays(SwitchWatchCalc.WATCH_TTL_DAYS + 2).toEpochDay()
        val expired = watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = expiredDay))

        assertNull(useCase.startWatchForSale(savedSale(sale(day = expiredDay)), today))

        assertEquals(expired, watches.watches.value.single().id)
        assertNull(watches.watches.value.single().sellTransactionId)
    }

    // --- "Köpte den här" stänger bevakningen ---

    private suspend fun openWatch(sourceRecordId: Long? = 7, candidates: List<String> = listOf("SE_KOP")): Long =
        watches.start(
            SwitchWatch(
                sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.minusDays(3).toEpochDay(),
                proceedsKr = 1_225.0, targetLevel = 3, sourceRecordId = sourceRecordId,
                candidates = candidates.map { SwitchWatchCandidate(isin = it, name = it) },
            ),
        )

    @Test
    fun `kop av den foreslagna fonden stanger bevakningen utan transaktion och kvitterar forslaget`() = runTest {
        setUpPlan()
        val watchId = openWatch()

        assertTrue(useCase.markBought(watchId, boughtIsin = "SE_KOP", today = today))

        val watch = watches.watches.value.single()
        assertFalse(watch.isOpen)
        assertEquals(SwitchWatchCloseReason.KOPT, watch.closeReason)
        assertEquals("SE_KOP", watch.boughtIsin)
        // Ingen transaktion är känd — ingen koppling påstås (#144); dagen är kvitteringens.
        assertNull(watch.buyTransactionId)
        assertEquals(today.toEpochDay(), watch.closedAtEpochDay)
        assertEquals(true, records.records.value.single().followed)
    }

    @Test
    fun `forslaget slas upp riktat - aldrig hela historiken`() = runTest {
        setUpPlan()
        val watchId = openWatch()
        records.observeHistoryCalls = 0

        useCase.markBought(watchId, boughtIsin = "SE_KOP", today = today)

        assertEquals(0, records.observeHistoryCalls)
    }

    @Test
    fun `kop av en annan fond stanger bevakningen men kvitterar inte forslaget`() = runTest {
        // Facit mäter följda råd (SET-5) — ett byte till något annat är inte rådet som följdes.
        setUpPlan()
        val watchId = openWatch()

        useCase.markBought(watchId, boughtIsin = "SE_ANNAN", today = today)

        val watch = watches.watches.value.single()
        assertEquals("SE_ANNAN", watch.boughtIsin)
        assertEquals(SwitchWatchCloseReason.KOPT, watch.closeReason)
        assertNull(records.records.value.single().followed)
    }

    @Test
    fun `kop utan kand fond stanger utan att pasta vad som koptes`() = runTest {
        setUpPlan()
        val watchId = openWatch()

        useCase.markBought(watchId, boughtIsin = null, today = today)

        val watch = watches.watches.value.single()
        assertFalse(watch.isOpen)
        assertNull(watch.boughtIsin)
        assertNull(records.records.value.single().followed)
    }

    @Test
    fun `ett andra avslut skriver inte over det forsta och kvitterar inte`() = runTest {
        setUpPlan()
        val watchId = openWatch()
        watches.close(watchId, SwitchWatchCloseReason.AVBRUTEN, today.minusDays(1))

        assertFalse(useCase.markBought(watchId, boughtIsin = "SE_KOP", today = today))

        assertEquals(SwitchWatchCloseReason.AVBRUTEN, watches.watches.value.single().closeReason)
        assertNull(records.records.value.single().followed)
        assertFalse(useCase.markBought(404, boughtIsin = "SE_KOP", today = today))
    }

    @Test
    fun `bara anropet vars stangning traffade raden kvitterar`() = runTest {
        // Avgörs av uppdateringens radantal, inte av en läsning före: två avslut som läser samma
        // öppna rad får inte kvittera två gånger eller båda returnera sant.
        setUpPlan()
        val watchId = openWatch()
        watches.closeResult = false

        assertFalse(useCase.markBought(watchId, boughtIsin = "SE_KOP", today = today))
        assertNull(records.records.value.single().followed)
    }

    // --- Bevakning ur ett förslag utan transaktion ("Bevaka alternativ" på Beslut/Fonddetalj) ---

    @Test
    fun `bevakning ur ett forslag startas en gang med dagens datum och utan transaktion`() = runTest {
        val suggestion = SwitchPlanResolver.Suggestion(
            recordId = 7, planIndex = 0, sellIsin = "SE_SALJ", buyIsin = "SE_KOP", sellFundName = "Såld fond",
            buyFundName = "Köpt fond", fromLevel = 5, toLevel = 3, feeDeltaPercent = 0.0, switchValueKr = 4_200.0, followed = true,
        )

        val first = useCase.startWatchFromSuggestion(suggestion, today)
        val second = useCase.startWatchFromSuggestion(suggestion, today)

        assertNull(second)
        val watch = watches.watches.value.single()
        assertEquals(first, watch.id)
        assertEquals(today.toEpochDay(), watch.soldAtEpochDay)
        assertEquals(4_200.0, watch.proceedsKr!!, 1e-9)
        assertEquals(3, watch.targetLevel)
        assertEquals(7L, watch.sourceRecordId)
        assertNull(watch.sellTransactionId)
    }
}
