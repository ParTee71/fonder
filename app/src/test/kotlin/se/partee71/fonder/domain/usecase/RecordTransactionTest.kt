package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.data.repository.FakeCachedPriceRepository
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.data.repository.FakeTransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCandidateSource
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.worker.FakeFundPriceRefreshScheduler

/**
 * Den enda skrivvägen för transaktioner (BYT-2, issue #143/#144): en sparad transaktion rör bytet
 * **bara** enligt användarens svar på väljaren ([SwitchDecision]) — aldrig på en gissning — och
 * varje skrivning begär **en** omräkning av planen (BYT-3). Bytets övriga handlingar: "Bevaka
 * alternativ" i Händelser ([RecordTransaction.startWatchForSale]) och ur ett förslag
 * ([RecordTransaction.startWatchFromSuggestion]), köpet som avslutar bevakningen ("Köpte den här"
 * går via köpformuläret, [SwitchDecision.ClosesWatch]) och kopplingen till en bevakning från en annan dag efter fråga
 * ([SwitchDecision.LinksWatch], i samma skrivning som säljet). En bevakning per säljtransaktion oavsett startväg (BYT-1).
 */
class RecordTransactionTest {

    private val today: LocalDate = LocalDate.of(2026, 10, 12)

    private val transactions = FakeTransactionRepository()
    private val watches = FakeSwitchWatchRepository()
    private val records = FakeSuggestionRecordRepository()
    private val metadata = FakeFundMetadataRepository()
    private val scheduler = FakeFundPriceRefreshScheduler()

    /** Riskprofilen förvalet läser (issue #150) — null som i de flesta testerna: då är förvalet säljfondens egen nivå. */
    private var riskProfile: RiskProfile? = null
    private val prices = FakeCachedPriceRepository()

    private val useCase = RecordTransaction(
        transactions,
        watches,
        records,
        metadata,
        scheduler,
        TargetLevelPreselection({ riskProfile }, transactions, prices, watches, records)
    )

    private val sellFund = Fund(fundId = "SHB1", name = "Såld fond", isin = "SE_SALJ")
    private val buyFund = Fund(fundId = "SE_KOP", name = "Köpt fond", isin = "SE_KOP")

    private fun meta(isin: String, risk: Int?) = FundMetadata(
        isin = isin, name = "Fond $isin", orderbookId = isin, totalFee = 0.4, managementFee = 0.4,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList()
    )

    private fun sale(fundId: String = sellFund.fundId, day: Long = today.toEpochDay(), shares: Double = 10.0, price: Double = 125.0, fee: Double = 25.0) = Transaction(fundId = fundId, type = TransactionType.SALJ, epochDay = day, shares = shares, pricePerShare = price, fee = fee)

    private fun purchase(fundId: String = buyFund.fundId, day: Long = today.toEpochDay()) = Transaction(fundId = fundId, type = TransactionType.KOP, epochDay = day, shares = 20.0, pricePerShare = 60.0)

    private fun planRecord(id: Long = 7, buyIsin: String = "SE_KOP") = SuggestionRecord(
        id = id,
        suggestedAtEpochDay = today.minusDays(1).toEpochDay(),
        planIndex = 0,
        sellIsin = "SE_SALJ",
        buyIsin = buyIsin,
        sellNavAtSuggestion = 120.0,
        buyNavAtSuggestion = 60.0,
        switchValueKr = 4_200.0
    )

    private suspend fun setUpPlan() {
        transactions.upsertFund(sellFund)
        transactions.upsertFund(buyFund)
        records.record(planRecord())
        metadata.metadataByIsin = mapOf("SE_SALJ" to meta("SE_SALJ", risk = 5), "SE_KOP" to meta("SE_KOP", risk = 3))
    }

    private fun entry(transaction: Transaction, decision: SwitchDecision = SwitchDecision.None) = RecordTransaction.Entry(transaction, decision)

    /** Sparar säljet utan bytesval och returnerar det med sitt id — som Händelser ser det. */
    private suspend fun savedSale(sale: Transaction = sale()): Transaction = sale.copy(id = useCase.record(entry(sale), today).id)

    // --- "Inget byte": transaktionen skrivs, inget byte rörs ---

    @Test
    fun `record utan bytesval skriver transaktionen och ror ingen bevakning`() = runTest {
        // En bevakning användaren startat ur ett förslag samma dag är inte säljets förrän
        // användaren säger det — inget gissas.
        setUpPlan()
        watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.toEpochDay(), sourceRecordId = 7))

        val recorded = useCase.record(entry(sale()), today)

        assertEquals(recorded.id, transactions.transactions.value.single().id)
        assertNull(recorded.watchId)
        val untouched = watches.watches.value.single()
        assertNull(untouched.sellTransactionId)
        assertNull(untouched.proceedsKr)
        assertTrue(untouched.isOpen)
    }

    @Test
    fun `recordAll skriver raderna i given ordning`() = runTest {
        setUpPlan()

        val recorded = useCase.recordAll(
            listOf(
                entry(sale(day = today.minusDays(5).toEpochDay())),
                entry(purchase(day = today.minusDays(4).toEpochDay())),
                entry(purchase(day = today.minusDays(3).toEpochDay()))
            ),
            today
        )

        assertEquals(recorded.map { it.id }, transactions.transactions.value.map { it.id })
        assertEquals(listOf(TransactionType.SALJ, TransactionType.KOP, TransactionType.KOP), transactions.transactions.value.map { it.type })
        assertTrue(watches.watches.value.isEmpty())
    }

    @Test
    fun `en tom import skriver ingenting och raknar inte om planen`() = runTest {
        assertTrue(useCase.recordAll(emptyList(), today).isEmpty())
        assertTrue(transactions.transactions.value.isEmpty())
        assertEquals(0, scheduler.switchPlanScans)
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

    // --- Planen räknas om efter varje skrivning, en gång, utan forcerad kursuppdatering (BYT-3, HEM-8) ---

    @Test
    fun `record och recordAll begar en omrakning var - aldrig forcerad`() = runTest {
        setUpPlan()

        useCase.record(entry(sale()), today)
        assertEquals(1, scheduler.switchPlanScans)

        useCase.recordAll(listOf(entry(purchase()), entry(purchase()), entry(purchase())), today)
        assertEquals("en import begär en omräkning, inte en per rad", 2, scheduler.switchPlanScans)
        assertEquals(0, scheduler.forcedSwitchPlanScans)
    }

    @Test
    fun `delete och startWatchForSale begar varsin omrakning`() = runTest {
        setUpPlan()
        val sale = savedSale()
        val before = scheduler.switchPlanScans

        useCase.startWatchForSale(sale, today)
        assertEquals(before + 1, scheduler.switchPlanScans)

        useCase.delete(sale.id)
        assertEquals(before + 2, scheduler.switchPlanScans)
        assertEquals(0, scheduler.forcedSwitchPlanScans)
    }

    @Test
    fun `en bevakning ur ett forslag utan transaktion begar ingen omrakning`() = runTest {
        // Utan säljtransaktion räknas den inte som likvid på väg — planen är oförändrad.
        setUpPlan()

        useCase.startWatchFromSuggestion(suggestion(), today)

        assertEquals(0, scheduler.switchPlanScans)
    }

    // --- Säljets bytesval: planrad eller fristående ---

    @Test
    fun `salj for en planrad startar bevakningen ur raden med radens malniva och koppling`() = runTest {
        setUpPlan()
        val soldAt = today.minusDays(1).toEpochDay()

        val recorded = useCase.record(entry(sale(day = soldAt), SwitchDecision.ForSuggestion(recordId = 7, toLevel = 3)), today)

        val watch = watches.watches.value.single()
        assertEquals(watch.id, recorded.watchId)
        assertEquals(recorded.id, watch.sellTransactionId)
        assertEquals(7L, watch.sourceRecordId)
        // Målnivån ur raden (3), inte säljfondens egen (5).
        assertEquals(3, watch.targetLevel)
        assertEquals(soldAt, watch.soldAtEpochDay)
        assertEquals(10.0 * 125.0 - 25.0, watch.proceedsKr!!, 1e-9)
        assertTrue(watch.isOpen)
    }

    @Test
    fun `salj for en planrad som inte finns langre startar fristaende`() = runTest {
        // Ett påhittat rad-id vore ett påstående — bevakningen får säljfondens egen nivå ur cachen.
        setUpPlan()

        useCase.record(entry(sale(), SwitchDecision.ForSuggestion(recordId = 404, toLevel = 3)), today)

        val watch = watches.watches.value.single()
        assertNull(watch.sourceRecordId)
        assertEquals(5, watch.targetLevel)
        assertEquals(transactions.transactions.value.single().id, watch.sellTransactionId)
    }

    @Test
    fun `fristaende salj startar bevakningen som Handelser gor`() = runTest {
        setUpPlan()

        val recorded = useCase.record(entry(sale(), SwitchDecision.Standalone), today)

        val watch = watches.watches.value.single()
        assertEquals(watch.id, recorded.watchId)
        assertNull(watch.sourceRecordId)
        assertEquals(5, watch.targetLevel)
        assertEquals(recorded.id, watch.sellTransactionId)
    }

    @Test
    fun `salj for en planrad kopplar en okopplad bevakning samma dag i stallet for att starta en ny`() = runTest {
        setUpPlan()
        val existing = watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.toEpochDay(), sourceRecordId = 7, targetLevel = 3))

        val recorded = useCase.record(entry(sale(), SwitchDecision.ForSuggestion(recordId = 7, toLevel = 3)), today)

        assertEquals(existing, recorded.watchId)
        assertEquals(recorded.id, watches.watches.value.single().sellTransactionId)
    }

    @Test
    fun `ett bytesval for salj pa ett kop och ett kopval pa ett salj gor ingenting`() = runTest {
        // Väljaren visar bara giltiga val; ett felriktat val får aldrig starta eller stänga något.
        setUpPlan()
        val open = openWatch()

        val purchase = useCase.record(entry(purchase(), SwitchDecision.Standalone), today)
        val sale = useCase.record(entry(sale(), SwitchDecision.ClosesWatch(open)), today)

        assertNull(purchase.watchId)
        assertNull(sale.watchId)
        assertEquals(1, watches.watches.value.size)
        assertTrue(watches.watches.value.single().isOpen)
    }

    @Test
    fun `salj av en fond utan ISIN med fristaende val sparas men bevakas inte`() = runTest {
        setUpPlan()
        transactions.upsertFund(Fund(fundId = "SHB9", name = "Utan ISIN", isin = null))

        val recorded = useCase.record(entry(sale(fundId = "SHB9"), SwitchDecision.Standalone), today)

        assertNull(recorded.watchId)
        assertEquals(1, transactions.transactions.value.size)
        assertTrue(watches.watches.value.isEmpty())
    }

    // --- Köpets bytesval: avslutar bevakningen med köptransaktionen ---

    @Test
    fun `kop som avslutar bevakningen stanger pa kopdagen med koptransaktionen och kvitterar forslaget`() = runTest {
        setUpPlan()
        val watchId = openWatch()
        val boughtAt = today.minusDays(1).toEpochDay()

        val recorded = useCase.record(entry(purchase(day = boughtAt), SwitchDecision.ClosesWatch(watchId)), today)

        val watch = watches.watches.value.single()
        assertEquals(watchId, recorded.watchId)
        assertFalse(watch.isOpen)
        assertEquals(SwitchWatchCloseReason.KOPT, watch.closeReason)
        assertEquals("SE_KOP", watch.boughtIsin)
        assertEquals(recorded.id, watch.buyTransactionId)
        // Köpdagen, inte dagen transaktionen registrerades.
        assertEquals(boughtAt, watch.closedAtEpochDay)
        assertEquals(true, records.records.value.single().followed)
    }

    @Test
    fun `kop av en annan fond som avslutar bevakningen kvitterar inte forslaget`() = runTest {
        setUpPlan()
        transactions.upsertFund(Fund(fundId = "SE_ANNAN", name = "Annan", isin = "SE_ANNAN"))
        val watchId = openWatch()

        useCase.record(entry(purchase(fundId = "SE_ANNAN"), SwitchDecision.ClosesWatch(watchId)), today)

        val watch = watches.watches.value.single()
        assertEquals("SE_ANNAN", watch.boughtIsin)
        assertEquals(SwitchWatchCloseReason.KOPT, watch.closeReason)
        assertNull(records.records.value.single().followed)
    }

    @Test
    fun `kop mot en redan stangd bevakning sparas men stanger inget`() = runTest {
        setUpPlan()
        val watchId = openWatch()
        watches.close(watchId, SwitchWatchCloseReason.AVBRUTEN, today.minusDays(1))

        val recorded = useCase.record(entry(purchase(), SwitchDecision.ClosesWatch(watchId)), today)

        assertNull(recorded.watchId)
        assertEquals(1, transactions.transactions.value.size)
        assertEquals(SwitchWatchCloseReason.AVBRUTEN, watches.watches.value.single().closeReason)
        assertNull(records.records.value.single().followed)
    }

    @Test
    fun `i en import kan kopet avsluta bevakningen som saljraden i samma import startar`() = runTest {
        setUpPlan()
        val soldAt = today.minusDays(3).toEpochDay()

        val recorded = useCase.recordAll(
            listOf(
                entry(sale(day = soldAt), SwitchDecision.ForSuggestion(recordId = 7, toLevel = 3)),
                entry(purchase(day = today.minusDays(1).toEpochDay()), SwitchDecision.ClosesWatchOfEntry(entryIndex = 0))
            ),
            today
        )

        val watch = watches.watches.value.single()
        assertEquals(watch.id, recorded[0].watchId)
        assertEquals(watch.id, recorded[1].watchId)
        assertEquals(recorded[0].id, watch.sellTransactionId)
        assertEquals(recorded[1].id, watch.buyTransactionId)
        assertEquals(soldAt, watch.soldAtEpochDay)
        assertEquals(today.minusDays(1).toEpochDay(), watch.closedAtEpochDay)
        assertEquals(true, records.records.value.single().followed)
        assertEquals(1, scheduler.switchPlanScans)
    }

    @Test
    fun `ett kop som pekar pa en rad utan bevakning eller en senare rad behandlas som inget byte`() = runTest {
        setUpPlan()

        val recorded = useCase.recordAll(
            listOf(
                entry(purchase(), SwitchDecision.ClosesWatchOfEntry(entryIndex = 1)),
                entry(sale(), SwitchDecision.None),
                entry(purchase(), SwitchDecision.ClosesWatchOfEntry(entryIndex = 1))
            ),
            today
        )

        assertTrue(recorded.all { it.watchId == null })
        assertEquals(3, transactions.transactions.value.size)
        assertTrue(watches.watches.value.isEmpty())
    }

    // --- Koppling till en bevakning från en annan dag, efter fråga ---

    @Test
    fun `koppling fran annan dag flyttar saljdagen tar likviden och nollar nollpunkten men behaller kandidaterna`() = runTest {
        setUpPlan()
        val watchDay = today.minusDays(2).toEpochDay()
        val watchId = watches.start(
            SwitchWatch(
                sellIsin = "SE_SALJ",
                sellFundName = "Såld fond",
                soldAtEpochDay = watchDay,
                proceedsKr = 4_200.0,
                targetLevel = 3,
                sourceRecordId = 7,
                candidates = listOf(
                    SwitchWatchCandidate(isin = "SE_KOP", name = "Köpt fond", navAtStart = 60.0, navAtStartEpochDay = watchDay),
                    SwitchWatchCandidate(isin = "SE_EGEN", name = "Egen", navAtStart = 10.0, navAtStartEpochDay = watchDay, source = SwitchWatchCandidateSource.MANUELL)
                )
            )
        )
        val soldAt = today.minusDays(4).toEpochDay()
        val before = scheduler.switchPlanScans

        val recorded = useCase.record(entry(sale(day = soldAt), SwitchDecision.LinksWatch(watchId)), today)

        assertEquals(watchId, recorded.watchId)
        val watch = watches.watches.value.single()
        assertEquals(recorded.id, watch.sellTransactionId)
        assertEquals(soldAt, watch.soldAtEpochDay)
        assertEquals(10.0 * 125.0 - 25.0, watch.proceedsKr!!, 1e-9)
        assertEquals(7L, watch.sourceRecordId)
        assertEquals(listOf("SE_KOP", "SE_EGEN"), watch.candidates.map { it.isin })
        assertEquals(SwitchWatchCandidateSource.MANUELL, watch.candidates.last().source)
        assertTrue(watch.candidates.all { it.navAtStart == null && it.navAtStartEpochDay == null })
        assertEquals(before + 1, scheduler.switchPlanScans)
    }

    @Test
    fun `koppling gors inte for ett kop en kopplad stangd utgangen eller okand bevakning - saljet sparas anda`() = runTest {
        setUpPlan()
        val linked = watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.minusDays(2).toEpochDay(), sellTransactionId = 99))
        val expired = watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.minusDays(SwitchWatchCalc.WATCH_TTL_DAYS + 2).toEpochDay()))
        val closed = watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.minusDays(2).toEpochDay()))
        watches.close(closed, SwitchWatchCloseReason.AVBRUTEN, today)
        val open = watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.minusDays(2).toEpochDay()))

        val results = listOf(
            useCase.record(entry(purchase(), SwitchDecision.LinksWatch(open)), today),
            useCase.record(entry(sale(), SwitchDecision.LinksWatch(linked)), today),
            useCase.record(entry(sale(), SwitchDecision.LinksWatch(expired)), today),
            useCase.record(entry(sale(), SwitchDecision.LinksWatch(closed)), today),
            useCase.record(entry(sale(), SwitchDecision.LinksWatch(404)), today)
        )

        assertTrue("kopplingen rapporteras som inte gjord", results.all { it.watchId == null })
        assertEquals("varje transaktion är sparad", 5, transactions.transactions.value.size)
        assertTrue(watches.watches.value.filter { it.id != linked }.all { it.sellTransactionId == null })
        assertEquals(5, scheduler.switchPlanScans)
    }

    @Test
    fun `en koppling som gjorde bevakningen utgangen pa den nya dagen gors inte`() = runTest {
        // Utgången prövas mot säljets dag, inte bevakningens gamla: annars stängde nästa
        // bakgrundskörning den direkt, utan att köpet någonsin kunde avsluta bytet.
        setUpPlan()
        val watchId = watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.minusDays(5).toEpochDay()))
        val tooOld = today.minusDays(SwitchWatchCalc.WATCH_TTL_DAYS + 1).toEpochDay()

        val recorded = useCase.record(entry(sale(day = tooOld), SwitchDecision.LinksWatch(watchId)), today)

        assertNull(recorded.watchId)
        assertNull(watches.watches.value.single().sellTransactionId)
        assertEquals(today.minusDays(5).toEpochDay(), watches.watches.value.single().soldAtEpochDay)
    }

    @Test
    fun `koppling samma dag kopplar utan att rora nollpunkten`() = runTest {
        // Säljet sparades som "Inget byte" och bevakningen startades ur förslaget samma dag — på
        // frågan svarade användaren ja. Dagen står, så kandidaternas nollpunkt är rätt och behålls.
        setUpPlan()
        val watchId = watches.start(
            SwitchWatch(
                sellIsin = "SE_SALJ",
                sellFundName = "Såld fond",
                soldAtEpochDay = today.toEpochDay(),
                sourceRecordId = 7,
                candidates = listOf(SwitchWatchCandidate(isin = "SE_KOP", name = "Köpt fond", navAtStart = 60.0, navAtStartEpochDay = today.toEpochDay()))
            )
        )
        val recorded = useCase.record(entry(sale(), SwitchDecision.LinksWatch(watchId)), today)

        assertEquals(watchId, recorded.watchId)
        val watch = watches.watches.value.single()
        assertEquals(recorded.id, watch.sellTransactionId)
        assertEquals(today.toEpochDay(), watch.soldAtEpochDay)
        assertEquals(10.0 * 125.0 - 25.0, watch.proceedsKr!!, 1e-9)
        assertEquals(60.0, watch.candidates.single().navAtStart!!, 1e-9)
    }

    @Test
    fun `i en import ser ett kop efter saljraden den koppling raden gjorde - samma pass`() = runTest {
        // Kopplingen görs i samma skrivning som säljet, före köpet — inte efteråt, då hade köpet
        // redan stängt bevakningen och kopplingen tyst misslyckats.
        setUpPlan()
        val watchId = watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.minusDays(2).toEpochDay(), sourceRecordId = 7))

        val recorded = useCase.recordAll(
            listOf(
                entry(sale(day = today.minusDays(2).toEpochDay()), SwitchDecision.LinksWatch(watchId)),
                entry(purchase(), SwitchDecision.ClosesWatch(watchId))
            ),
            today
        )

        assertEquals(listOf(watchId, watchId), recorded.map { it.watchId })
        val watch = watches.watches.value.single()
        assertEquals(recorded[0].id, watch.sellTransactionId)
        assertEquals(recorded[1].id, watch.buyTransactionId)
        assertFalse(watch.isOpen)
        assertEquals(true, records.records.value.single().followed)
        assertEquals(1, scheduler.switchPlanScans)
    }

    @Test
    fun `salj for planrad R kopplar en okopplad bevakning samma dag bara om den saknar forslag eller ar startad ur R - och tar da over raden`() = runTest {
        setUpPlan()
        records.record(planRecord(id = 8, buyIsin = "SE_ANNAN"))
        // Bevakning ur ett annat förslag (8) samma dag: inte R:s sälj — en ny startas ur R.
        val other = watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.toEpochDay(), sourceRecordId = 8, targetLevel = 4))

        val first = useCase.record(entry(sale(), SwitchDecision.ForSuggestion(recordId = 7, toLevel = 3)), today)

        assertTrue(first.watchId != null && first.watchId != other)
        val started = watches.watches.value.single { it.id == first.watchId }
        assertEquals(7L, started.sourceRecordId)
        assertEquals(3, started.targetLevel)
        assertNull(watches.watches.value.single { it.id == other }.sellTransactionId)

        // Bevakning utan förslag samma dag (fristående från Händelser): kopplas och tar över R.
        val plain = watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.minusDays(1).toEpochDay(), targetLevel = 5))
        val second = useCase.record(entry(sale(day = today.minusDays(1).toEpochDay()), SwitchDecision.ForSuggestion(recordId = 7, toLevel = 3)), today)

        assertEquals(plain, second.watchId)
        val adopted = watches.watches.value.single { it.id == plain }
        assertEquals(7L, adopted.sourceRecordId)
        assertEquals(3, adopted.targetLevel)
        assertEquals(second.id, adopted.sellTransactionId)
    }

    @Test
    fun `en koppling som inte skrevs ger inget watchId - en koprad kan da inte stanga fel bevakning`() = runTest {
        // Avgörs av uppdateringens radantal: förlorade kopplingen mot en samtidig skrivning är
        // bevakningen inte säljets, och ett köp i samma import får inte peka på den.
        setUpPlan()
        val existing = watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.toEpochDay()))
        watches.linkResult = false

        val recorded = useCase.recordAll(
            listOf(
                entry(sale(), SwitchDecision.Standalone),
                entry(purchase(), SwitchDecision.ClosesWatchOfEntry(entryIndex = 0))
            ),
            today
        )

        assertNull(recorded[0].watchId)
        assertNull(recorded[1].watchId)
        assertTrue(watches.watches.value.single { it.id == existing }.isOpen)
        assertNull(useCase.startWatchForSale(savedSale(sale(shares = 2.0)), today))
    }

    @Test
    fun `fonderna lases en gang per import inte per rad`() = runTest {
        setUpPlan()
        transactions.fundsReads = 0

        useCase.recordAll(
            listOf(
                entry(sale(), SwitchDecision.Standalone),
                entry(purchase(), SwitchDecision.ClosesWatchOfEntry(entryIndex = 0)),
                entry(purchase(), SwitchDecision.None)
            ),
            today
        )

        assertEquals(1, transactions.fundsReads)
    }

    @Test
    fun `fristaende salj kopplar en okopplad bevakning samma dag oavsett dess forslag`() = runTest {
        setUpPlan()
        val fromSuggestion = watches.start(SwitchWatch(sellIsin = "SE_SALJ", sellFundName = "Såld fond", soldAtEpochDay = today.toEpochDay(), sourceRecordId = 7, targetLevel = 3))

        val recorded = useCase.record(entry(sale(), SwitchDecision.Standalone), today)

        assertEquals(fromSuggestion, recorded.watchId)
        assertEquals(7L, watches.watches.value.single().sourceRecordId)
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

    /**
     * Portfölj för förvalet (issue #150): säljfonden (nivå 5) och en fond på nivå 2 köpta tidigare,
     * kurser i cachen och mål 20/50/30 på nivå 2/3/5. Efter säljet (likvid 1 225 kr) är nivå 3 den
     * mest underviktade — inte säljfondens egen nivå.
     */
    private suspend fun setUpProfilePortfolio() {
        setUpPlan()
        val other = Fund(fundId = "SHB2", name = "Annan fond", isin = "SE_ANNAN")
        transactions.upsertFund(other)
        metadata.metadataByIsin = metadata.metadataByIsin + ("SE_ANNAN" to meta("SE_ANNAN", risk = 2))
        val earlier = today.minusDays(30).toEpochDay()
        transactions.addTransaction(purchase(fundId = sellFund.fundId, day = earlier))
        transactions.addTransaction(purchase(fundId = other.fundId, day = earlier))
        prices.latestPrices.value = mapOf(
            sellFund.fundId to FundPrice(sellFund.fundId, today.toEpochDay(), 125.0, "SEK"),
            other.fundId to FundPrice(other.fundId, today.toEpochDay(), 100.0, "SEK")
        )
        riskProfile = RiskProfile(targetAllocation = mapOf(2 to 0.2, 3 to 0.5, 5 to 0.3))
    }

    @Test
    fun `fristaende salj med riskprofil forvaljer den mest underviktade nivan`() = runTest {
        setUpProfilePortfolio()

        useCase.record(entry(sale(), SwitchDecision.Standalone), today)
        useCase.startWatchForSale(savedSale(sale(day = today.minusDays(1).toEpochDay())), today)

        // Båda fristående vägarna (väljaren och "Bevaka alternativ" i Händelser) förväljer på samma
        // sätt. Det första säljets likvid väntar redan på nivå 3 (BYT-3), så när resten av säljfonden
        // säljs är nivå 5 längst från målet (30 pp mot 22,5 pp) — ett gap, inte reserven.
        assertEquals(listOf(3, 5), watches.watches.value.map { it.targetLevel })
    }

    @Test
    fun `salj for en planrad behaller radens niva aven med riskprofil`() = runTest {
        setUpProfilePortfolio()

        useCase.record(entry(sale(), SwitchDecision.ForSuggestion(recordId = 7, toLevel = 2)), today)

        assertEquals(2, watches.watches.value.single().targetLevel)
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
                sellIsin = "SE_SALJ",
                sellFundName = "Såld fond",
                soldAtEpochDay = today.toEpochDay(),
                proceedsKr = 4_200.0,
                targetLevel = 3,
                sourceRecordId = 7,
                candidates = listOf(SwitchWatchCandidate(isin = "SE_KOP", name = "Köpt fond", navAtStart = 60.0, navAtStartEpochDay = today.toEpochDay()))
            )
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

    // --- Köpet som avslutar bevakningen (även "Köpte den här", via köpformuläret) ---

    private suspend fun openWatch(sourceRecordId: Long? = 7, candidates: List<String> = listOf("SE_KOP")): Long = watches.start(
        SwitchWatch(
            sellIsin = "SE_SALJ",
            sellFundName = "Såld fond",
            soldAtEpochDay = today.minusDays(3).toEpochDay(),
            proceedsKr = 1_225.0,
            targetLevel = 3,
            sourceRecordId = sourceRecordId,
            candidates = candidates.map { SwitchWatchCandidate(isin = it, name = it) }
        )
    )

    @Test
    fun `forslaget slas upp riktat - aldrig hela historiken`() = runTest {
        setUpPlan()
        val watchId = openWatch()
        records.observeHistoryCalls = 0

        useCase.record(entry(purchase(), SwitchDecision.ClosesWatch(watchId)), today)

        assertEquals(0, records.observeHistoryCalls)
    }

    @Test
    fun `kop av en fond utan ISIN stanger utan att pasta vad som koptes`() = runTest {
        setUpPlan()
        transactions.upsertFund(Fund(fundId = "SHB_UTAN", name = "Utan ISIN"))
        val watchId = openWatch()

        useCase.record(entry(purchase(fundId = "SHB_UTAN"), SwitchDecision.ClosesWatch(watchId)), today)

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

        assertNull(useCase.record(entry(purchase(), SwitchDecision.ClosesWatch(watchId)), today).watchId)

        assertEquals(SwitchWatchCloseReason.AVBRUTEN, watches.watches.value.single().closeReason)
        assertNull(records.records.value.single().followed)
        assertNull(useCase.record(entry(purchase(), SwitchDecision.ClosesWatch(404)), today).watchId)
    }

    @Test
    fun `bara anropet vars stangning traffade raden kvitterar`() = runTest {
        // Avgörs av uppdateringens radantal, inte av en läsning före: två avslut som läser samma
        // öppna rad får inte kvittera två gånger eller båda returnera bevakningen.
        setUpPlan()
        val watchId = openWatch()
        watches.closeResult = false

        assertNull(useCase.record(entry(purchase(), SwitchDecision.ClosesWatch(watchId)), today).watchId)
        assertNull(records.records.value.single().followed)
    }

    // --- Bevakning ur ett förslag utan transaktion ("Bevaka alternativ" på Beslut/Fonddetalj) ---

    private fun suggestion() = SwitchPlanResolver.Suggestion(
        recordId = 7, planIndex = 0, sellIsin = "SE_SALJ", buyIsin = "SE_KOP", sellFundName = "Såld fond",
        buyFundName = "Köpt fond", fromLevel = 5, toLevel = 3, feeDeltaPercent = 0.0, switchValueKr = 4_200.0, followed = true
    )

    @Test
    fun `bevakning ur ett forslag startas en gang med dagens datum och utan transaktion`() = runTest {
        val suggestion = suggestion()

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
