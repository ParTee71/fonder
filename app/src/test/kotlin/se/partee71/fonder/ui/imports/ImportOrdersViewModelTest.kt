package se.partee71.fonder.ui.imports

import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.imports.PdfTextExtractor
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.data.repository.FakeSuggestionRecordRepository
import se.partee71.fonder.data.repository.FakeSwitchWatchRepository
import se.partee71.fonder.data.repository.FakeTransactionRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCatalog
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.RecordTransaction
import se.partee71.fonder.domain.usecase.SwitchDecision
import se.partee71.fonder.domain.usecase.SwitchOption
import se.partee71.fonder.testing.testTargetLevelPreselection
import se.partee71.fonder.ui.transaktioner.SwitchOptionLabel
import se.partee71.fonder.testing.testPreferencesRepository
import se.partee71.fonder.testing.testSwitchChoicesSource
import se.partee71.fonder.worker.FakeFundPriceRefreshScheduler
import java.time.LocalDate

private const val NORDEA_TEXT = """
Nordea Fonder AB
Småbolagsfond Sverige
ISIN: SE0003653302
Text Belopp Kurs Andelar Saldo andelar
In Självbetjäning 2020-03-13 5 000.00 294.36 16.9862 16.9862
"""

@OptIn(ExperimentalCoroutinesApi::class)
class ImportOrdersViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private val addedFunds: List<Fund> get() = fakeTransactionRepo.funds.value
    private val addedTransactions: List<Transaction> get() = fakeTransactionRepo.transactions.value
    private val handelsbankenFund = Fund(fundId = "SHB0000442", name = "Handelsbanken Sverige (A1 SEK)")
    private val catalog = FundCatalog(companies = emptyList(), funds = listOf(handelsbankenFund))
    private var findFundByIsinResult: Fund? = null
    private var refreshedFundId: String? = null
    private var refreshSinceCall: Triple<String, String, LocalDate>? = null

    private val fundsByIsin = mutableMapOf<String, Fund>()

    /** Den delade fake:n — bevakade fonder och skrivna transaktioner (med id 1, 2, … i skrivordning). */
    private val fakeTransactionRepo = FakeTransactionRepository()

    private val fakePriceRepo = object : FundPriceRepository {
        override suspend fun latestPrice(fundId: String): FundPrice? = null
        override fun observeLatestPrices(fundIds: List<String>): Flow<Map<String, FundPrice>> = flowOf(emptyMap())
        override suspend fun priceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): List<FundPrice> = emptyList()
        override fun observePriceHistory(fundId: String, fromEpochDay: Long, toEpochDay: Long): Flow<List<FundPrice>> = flowOf(emptyList())
        override suspend fun refresh(fundId: String, since: LocalDate?): Boolean {
            refreshedFundId = fundId
            return true
        }
        override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate): Boolean {
            refreshSinceCall = Triple(fundId, isin, since)
            return true
        }
        override suspend fun historyForIsin(isin: String, from: LocalDate, to: LocalDate): List<FundPrice> = emptyList()
        override suspend fun suggestIsin(fundName: String): String? = null
        override suspend fun findFundByIsin(isin: String): Fund? = fundsByIsin[isin] ?: findFundByIsinResult
        override suspend fun lookupIsin(fundId: String): String? = null
        override suspend fun fetchFundsForCompany(companyId: String): List<Fund>? = emptyList()
        override suspend fun fetchFundCatalog(): FundCatalog = catalog
    }

    @get:Rule
    val tempFolder = TemporaryFolder()
    private lateinit var preferences: PreferencesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        preferences = testPreferencesRepository(dispatcher, tempFolder, "import_orders_test")
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private val scheduler = FakeFundPriceRefreshScheduler()
    private val suggestionRepo = FakeSuggestionRecordRepository()
    private val switchWatchRepo = FakeSwitchWatchRepository()
    private val metadataRepo = FakeFundMetadataRepository()
    private val recordTransaction = RecordTransaction(
        fakeTransactionRepo, switchWatchRepo, suggestionRepo, metadataRepo, scheduler,
        testTargetLevelPreselection(fakeTransactionRepo, switchWatchRepo),
    )

    private fun viewModel(extractor: PdfTextExtractor, priceRepo: FundPriceRepository = fakePriceRepo) =
        ImportOrdersViewModel(
            fakeTransactionRepo, priceRepo, extractor, recordTransaction,
            testSwitchChoicesSource(preferences, suggestionRepo, switchWatchRepo, metadataRepo = metadataRepo),
        )

    @Test
    fun `parsar och matchar fond via ISIN fran en PDF`() = runTest(dispatcher) {
        findFundByIsinResult = Fund(fundId = "SE0003653302", name = "Nordea Småbolagsfond Sverige", currency = "SEK", isin = "SE0003653302")
        val vm = viewModel(PdfTextExtractor { NORDEA_TEXT })

        vm.uiState.test {
            assertTrue(awaitItem().rows.isEmpty())
            vm.onFilesSelected(listOf("nota.pdf" to ByteArray(0)))
            var state = awaitItem()
            while (state.loading || !state.switchReady) state = awaitItem()

            assertEquals(1, state.rows.size)
            val row = state.rows.first()
            assertEquals("SE0003653302", row.matchedFund?.fundId)
            assertEquals(1.0, row.matchConfidence)
            assertEquals("16.9862", row.sharesText)
            assertEquals("294.36", row.priceText)
            assertEquals(LocalDate.of(2020, 3, 13), row.date)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fallback till namnmatchning mot Handelsbankens katalog om ISIN inte kanns igen`() = runTest(dispatcher) {
        val text = """
            Handelsbanken Fonder AB
            Handelsbanken Sverige (A1 SEK)
            ISIN: SE0000582033
            Text Belopp Kurs Andelar Saldo andelar
            In Självbetjäning 2020-03-13 5 000.00 294.36 16.9862 16.9862
        """.trimIndent()
        val vm = viewModel(PdfTextExtractor { text })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(listOf("nota.pdf" to ByteArray(0)))
            var state = awaitItem()
            while (state.loading || !state.switchReady) state = awaitItem()

            assertEquals(handelsbankenFund, state.rows.first().matchedFund)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fil som inte kan tolkas alls ger felstate`() = runTest(dispatcher) {
        val vm = viewModel(PdfTextExtractor { "inte en avräkningsnota" })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(listOf("skrap.pdf" to ByteArray(0)))
            var state = awaitItem()
            while (state.loading || !state.switchReady) state = awaitItem()

            assertEquals(ImportOrdersError.NONE_PARSED, state.error)
            assertTrue(state.rows.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `flera filer samtidigt ger en rad per parsad transaktion`() = runTest(dispatcher) {
        findFundByIsinResult = Fund(fundId = "SE0003653302", name = "Nordea Småbolagsfond Sverige", currency = "SEK", isin = "SE0003653302")
        val extractorPerFile: (String) -> String = { fileName ->
            if (fileName == "unparsable.pdf") "ingen avräkningsnota" else NORDEA_TEXT
        }
        val vm = viewModel(PdfTextExtractor { bytes -> extractorPerFile(String(bytes)) })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(
                listOf(
                    "nota1.pdf" to "nota1.pdf".toByteArray(),
                    "nota2.pdf" to "nota2.pdf".toByteArray(),
                    "unparsable.pdf" to "unparsable.pdf".toByteArray(),
                ),
            )
            var state = awaitItem()
            while (state.loading || !state.switchReady) state = awaitItem()

            assertEquals(2, state.rows.size)
            assertEquals(listOf("unparsable.pdf"), state.unparsedFileNames)
            assertNull(state.error)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `manuellt rattad andel med svenskt decimalkomma importeras korrekt`() = runTest(dispatcher) {
        // Regression: fälten redigeras med KeyboardType.Decimal, som på ett svenskt
        // tangentbord ofta bara erbjuder komma — en rättelse med komma ska inte tyst göra
        // raden ej importerbar.
        findFundByIsinResult = Fund(fundId = "SE0003653302", name = "Nordea Småbolagsfond Sverige", currency = "SEK", isin = "SE0003653302")
        val vm = viewModel(PdfTextExtractor { NORDEA_TEXT })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(listOf("nota.pdf" to ByteArray(0)))
            var state = awaitItem()
            while (state.loading || !state.switchReady) state = awaitItem()

            val transaction = state.rows.first().transaction
            vm.onSharesTextChange(transaction, "16,9862")
            state = awaitItem()
            assertTrue(state.rows.first().readyToImport)

            vm.import()
            state = awaitItem()
            while (!state.imported) state = awaitItem()

            assertEquals(16.9862, addedTransactions.first().shares, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `import skapar fond och transaktion fran bekraftade rader`() = runTest(dispatcher) {
        findFundByIsinResult = Fund(fundId = "SE0003653302", name = "Nordea Småbolagsfond Sverige", currency = "SEK", isin = "SE0003653302")
        val vm = viewModel(PdfTextExtractor { NORDEA_TEXT })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(listOf("nota.pdf" to ByteArray(0)))
            var state = awaitItem()
            while (state.loading || !state.switchReady) state = awaitItem()

            vm.import()
            state = awaitItem()
            while (!state.imported) state = awaitItem()

            assertEquals(1, addedFunds.size)
            assertEquals("SE0003653302", addedFunds.first().isin)
            assertEquals(1, addedTransactions.size)
            val tx = addedTransactions.first()
            assertEquals(TransactionType.KOP, tx.type)
            assertEquals(16.9862, tx.shares, 1e-9)
            assertEquals(294.36, tx.pricePerShare, 1e-9)
            assertEquals(LocalDate.of(2020, 3, 13).toEpochDay(), tx.epochDay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `matchad rad via ISIN triggar refreshSince, inte refresh`() = runTest(dispatcher) {
        // Regression för issue #19: till skillnad från ImportHoldingsViewModel hämtade det
        // här flödet tidigare aldrig någon kurs alls — en importerad fond fick ingen cachad
        // kurs förrän något annat råkade hämta den. fakePriceRepo.latestPrice returnerar
        // null som standard, dvs. fonden har ingen kurs sedan tidigare.
        findFundByIsinResult = Fund(fundId = "SE0003653302", name = "Nordea Småbolagsfond Sverige", currency = "SEK", isin = "SE0003653302")
        val vm = viewModel(PdfTextExtractor { NORDEA_TEXT })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(listOf("nota.pdf" to ByteArray(0)))
            var state = awaitItem()
            while (state.loading || !state.switchReady) state = awaitItem()

            assertEquals(Triple("SE0003653302", "SE0003653302", LocalDate.of(2020, 3, 13)), refreshSinceCall)
            assertNull(refreshedFundId)
            assertFalse(state.rows.first().priceFetchFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `matchad rad via Handelsbanken-katalogen triggar refresh, inte refreshSince`() = runTest(dispatcher) {
        val text = """
            Handelsbanken Fonder AB
            Handelsbanken Sverige (A1 SEK)
            ISIN: SE0000582033
            Text Belopp Kurs Andelar Saldo andelar
            In Självbetjäning 2020-03-13 5 000.00 294.36 16.9862 16.9862
        """.trimIndent()
        val vm = viewModel(PdfTextExtractor { text })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(listOf("nota.pdf" to ByteArray(0)))
            var state = awaitItem()
            while (state.loading || !state.switchReady) state = awaitItem()

            assertEquals(handelsbankenFund.fundId, refreshedFundId)
            assertNull(refreshSinceCall)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hoppar over kursuppdatering nar fonden redan har en aktuell cachad kurs`() = runTest(dispatcher) {
        findFundByIsinResult = Fund(fundId = "SE0003653302", name = "Nordea Småbolagsfond Sverige", currency = "SEK", isin = "SE0003653302")
        val priceRepoMedFarskKurs = object : FundPriceRepository by fakePriceRepo {
            override suspend fun latestPrice(fundId: String): FundPrice =
                FundPrice(fundId = fundId, epochDay = LocalDate.now().toEpochDay(), nav = 294.36, currency = "SEK")
        }
        val vm = viewModel(PdfTextExtractor { NORDEA_TEXT }, priceRepoMedFarskKurs)

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(listOf("nota.pdf" to ByteArray(0)))
            var state = awaitItem()
            while (state.loading || !state.switchReady) state = awaitItem()

            assertNull(refreshedFundId)
            assertNull(refreshSinceCall)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `misslyckad kurshamtning markerar raden`() = runTest(dispatcher) {
        findFundByIsinResult = Fund(fundId = "SE0003653302", name = "Nordea Småbolagsfond Sverige", currency = "SEK", isin = "SE0003653302")
        val priceRepoMedMisslyckadHamtning = object : FundPriceRepository by fakePriceRepo {
            override suspend fun refreshSince(fundId: String, isin: String, since: LocalDate): Boolean {
                refreshSinceCall = Triple(fundId, isin, since)
                return false
            }
        }
        val vm = viewModel(PdfTextExtractor { NORDEA_TEXT }, priceRepoMedMisslyckadHamtning)

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(listOf("nota.pdf" to ByteArray(0)))
            var state = awaitItem()
            while (state.loading || !state.switchReady) state = awaitItem()

            assertTrue(state.rows.first().priceFetchFailed)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `importen raknar om bytesplanen en gang, inte per rad`() = runTest(dispatcher) {
        // Varje skanning kostar en forcerad kursuppdatering plus källfrågor — en import med många
        // rader ska koalesceras till en (BYT-2).
        findFundByIsinResult = Fund(fundId = "SE0003653302", name = "Nordea Småbolagsfond Sverige", currency = "SEK", isin = "SE0003653302")
        val vm = viewModel(PdfTextExtractor { NORDEA_TEXT })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(listOf("nota1.pdf" to ByteArray(0), "nota2.pdf" to ByteArray(0), "nota3.pdf" to ByteArray(0)))
            var state = awaitItem()
            while (state.loading || !state.switchReady) state = awaitItem()
            assertEquals(3, state.rows.size)

            vm.import()
            state = awaitItem()
            while (!state.imported) state = awaitItem()

            assertEquals(3, addedTransactions.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Bytesväljaren per rad (BYT-2/IMP-6, issue #144) ---

    private fun nota(fundName: String, isin: String, line: String) = """
        Fonder AB
        $fundName
        ISIN: $isin
        Text Belopp Kurs Andelar Saldo andelar
        $line
    """.trimIndent()

    private val saleNota = nota("Sverige Småbolag", "SE0004297927", "Ut Självbetjäning 2026-10-10 18 400.00 810.00 22.7160 0.0000")
    private val buyNota = nota("Global Indexnära", "SE0000581434", "In Självbetjäning 2026-10-13 18 400.00 438.46 41.9649 41.9649")
    private val otherBuyNota = nota("Annan fond", "SE0000000018", "In Självbetjäning 2026-10-13 5 000.00 100.00 50.0000 50.0000")

    private fun meta(isin: String, name: String, risk: Int) = FundMetadata(
        isin = isin, name = name, orderbookId = isin, totalFee = 0.5, managementFee = 0.5,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
    )

    private suspend fun setUpPlanAndFunds() {
        listOf(
            Fund(fundId = "SE0004297927", name = "Sverige Småbolag", isin = "SE0004297927"),
            Fund(fundId = "SE0000581434", name = "Global Indexnära", isin = "SE0000581434"),
            Fund(fundId = "SE0000000018", name = "Annan fond", isin = "SE0000000018"),
        ).forEach { fundsByIsin[it.isin!!] = it }
        metadataRepo.metadataByIsin = mapOf(
            "SE0004297927" to meta("SE0004297927", "Sverige Småbolag", 5),
            "SE0000581434" to meta("SE0000581434", "Global Indexnära", 3),
        )
        preferences.setAccountType(AccountType.ISK_KF)
        suggestionRepo.records.value = listOf(
            SuggestionRecord(
                id = 42, suggestedAtEpochDay = LocalDate.now().toEpochDay(), planIndex = 0,
                sellIsin = "SE0004297927", buyIsin = "SE0000581434",
                sellNavAtSuggestion = 800.0, buyNavAtSuggestion = 430.0, switchValueKr = 18_400.0,
            ),
        )
    }

    /** Filerna som (namn, innehåll) — extraktorn ger tillbaka innehållet som text. */
    private fun files(vararg texts: String) = texts.mapIndexed { index, text -> "nota$index.pdf" to text.toByteArray() }

    @Test
    fun `saljen star fore kopen och kopet av forslagets kopfond avslutar bytet som saljraden startar`() = runTest(dispatcher) {
        setUpPlanAndFunds()
        val vm = viewModel(PdfTextExtractor { bytes -> String(bytes) })

        vm.uiState.test {
            awaitItem()
            // Köpet först i filordningen — importen visar och skriver ändå sälj före köp.
            vm.onFilesSelected(files(buyNota, saleNota))
            var state = awaitItem()
            while (state.loading || !state.switchReady || state.rows.any { it.switchField == null }) state = awaitItem()

            assertEquals(listOf(TransactionType.SALJ, TransactionType.KOP), state.rows.map { it.transaction.type })
            assertEquals(SwitchOption.Decide(SwitchDecision.ForSuggestion(42, 3)), state.rows[0].switchField?.option)
            assertEquals(SwitchOption.Decide(SwitchDecision.ClosesWatchOfEntry(0)), state.rows[1].switchField?.option)
            assertEquals(
                SwitchOptionLabel.SaleRow("Sverige Småbolag"),
                state.rows[1].switchField?.selected?.label,
            )
            assertTrue(state.hasSwitchQuestions)
            assertTrue(state.canImport)

            vm.import()
            while (!state.imported) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(TransactionType.SALJ, TransactionType.KOP), addedTransactions.map { it.type })
        val watch = switchWatchRepo.watches.value.single()
        assertEquals(42L, watch.sourceRecordId)
        assertEquals(1L, watch.sellTransactionId)
        assertEquals(2L, watch.buyTransactionId)
        assertFalse(watch.isOpen)
        // Köpet var den föreslagna fonden — förslaget räknas som följt i Facit (SET-5).
        assertEquals(true, suggestionRepo.records.value.single().followed)
        assertEquals("en import, en omräkning", 1, scheduler.switchPlanScans)
    }

    @Test
    fun `ett kop av en annan fond efter en fristaende saljrad kraver svar innan importen tands`() = runTest(dispatcher) {
        setUpPlanAndFunds()
        suggestionRepo.records.value = emptyList()
        val vm = viewModel(PdfTextExtractor { bytes -> String(bytes) })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(files(saleNota, otherBuyNota))
            var state = awaitItem()
            while (state.loading || !state.switchReady || state.rows.any { it.switchField == null }) state = awaitItem()

            // Säljet utan planrad: "Inget byte" förvalt, köpet har inget att koppla till.
            assertEquals(SwitchOption.Decide(SwitchDecision.None), state.rows[0].switchField?.option)
            assertTrue(state.rows[1].switchField!!.hidden)

            vm.onSwitchSelected(state.rows[0].transaction, SwitchOption.Decide(SwitchDecision.Standalone))
            state = awaitItem()
            assertTrue(state.rows[1].switchField!!.unanswered)
            assertFalse(state.canImport)

            vm.onSwitchSelected(state.rows[1].transaction, SwitchOption.Decide(SwitchDecision.ClosesWatchOfEntry(0)))
            state = awaitItem()
            assertTrue(state.canImport)

            vm.import()
            while (!state.imported) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        val watch = switchWatchRepo.watches.value.single()
        assertEquals(1L, watch.sellTransactionId)
        assertEquals(2L, watch.buyTransactionId)
        assertEquals("SE0000000018", watch.boughtIsin)
    }

    @Test
    fun `ja pa fragan om en okopplad bevakning kopplas i samma skrivning sa kopet i importen kan avsluta den`() = runTest(dispatcher) {
        // Bevakningen startades ur förslaget på Beslut; nu importeras säljet och köpet. Kopplingen
        // måste göras före köpet i samma pass — efteråt hade köpet redan stängt bevakningen.
        setUpPlanAndFunds()
        val watchId = switchWatchRepo.start(
            SwitchWatch(
                sellIsin = "SE0004297927", sellFundName = "Sverige Småbolag", soldAtEpochDay = LocalDate.of(2026, 10, 10).toEpochDay(),
                targetLevel = 3, sourceRecordId = 42, candidates = listOf(SwitchWatchCandidate(isin = "SE0000581434", name = "Global Indexnära")),
            ),
        )
        val vm = viewModel(PdfTextExtractor { bytes -> String(bytes) })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(files(saleNota, buyNota))
            var state = awaitItem()
            while (state.loading || !state.switchReady || state.rows.any { it.switchField == null }) state = awaitItem()

            assertTrue(state.rows[0].switchField!!.unanswered)
            vm.onSwitchSelected(state.rows[0].transaction, SwitchOption.LinkOtherDay(watchId))
            state = awaitItem()
            assertEquals(SwitchOption.Decide(SwitchDecision.ClosesWatch(watchId)), state.rows[1].switchField?.option)
            assertTrue(state.canImport)

            vm.import()
            while (!state.imported) state = awaitItem()
            assertEquals(0, state.linkFailedCount)
            cancelAndIgnoreRemainingEvents()
        }

        val watch = switchWatchRepo.watches.value.single()
        assertEquals(1L, watch.sellTransactionId)
        assertEquals(2L, watch.buyTransactionId)
        assertFalse(watch.isOpen)
        assertEquals(1, scheduler.switchPlanScans)
    }

    @Test
    fun `en koppling som inte kunde goras raknas i kvittot`() = runTest(dispatcher) {
        setUpPlanAndFunds()
        suggestionRepo.records.value = emptyList()
        val watchId = switchWatchRepo.start(
            SwitchWatch(sellIsin = "SE0004297927", sellFundName = "Sverige Småbolag", soldAtEpochDay = LocalDate.of(2026, 10, 10).toEpochDay(), targetLevel = 3),
        )
        switchWatchRepo.linkResult = false
        val vm = viewModel(PdfTextExtractor { bytes -> String(bytes) })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(files(saleNota))
            var state = awaitItem()
            while (state.loading || !state.switchReady || state.rows.any { it.switchField == null }) state = awaitItem()
            vm.onSwitchSelected(state.rows[0].transaction, SwitchOption.LinkOtherDay(watchId))
            state = awaitItem()

            vm.import()
            while (!state.imported) state = awaitItem()
            assertEquals(1, state.linkFailedCount)
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(1, addedTransactions.size)
        assertEquals(null, switchWatchRepo.watches.value.single().sellTransactionId)
    }

    @Test
    fun `ett kop som pekar pa en bortvald saljrad blir inget byte`() = runTest(dispatcher) {
        setUpPlanAndFunds()
        val vm = viewModel(PdfTextExtractor { bytes -> String(bytes) })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(files(saleNota, buyNota))
            var state = awaitItem()
            while (state.loading || !state.switchReady || state.rows.any { it.switchField == null }) state = awaitItem()

            vm.onIncludedChange(state.rows[0].transaction, false)
            state = awaitItem()
            assertNull(state.rows[0].switchField)
            assertTrue(state.rows[1].switchField!!.hidden)

            vm.import()
            while (!state.imported) state = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(TransactionType.KOP), addedTransactions.map { it.type })
        assertTrue(switchWatchRepo.watches.value.isEmpty())
    }

    @Test
    fun `ett kop som pekar pa en saljrad som inte kan importeras slacker importen tills raden ratts`() = runTest(dispatcher) {
        // Frågorna och importen räknas över samma rader — ett besvarat avslut får aldrig tyst bli
        // "Inget byte" för att säljraden hoppas över (granskning #144).
        setUpPlanAndFunds()
        val vm = viewModel(PdfTextExtractor { bytes -> String(bytes) })

        vm.uiState.test {
            awaitItem()
            vm.onFilesSelected(files(saleNota, buyNota))
            var state = awaitItem()
            while (state.loading || !state.switchReady || state.rows.any { it.switchField == null }) state = awaitItem()
            val sale = state.rows[0].transaction
            assertEquals(SwitchOption.Decide(SwitchDecision.ClosesWatchOfEntry(0)), state.rows[1].switchField?.option)

            vm.onSharesTextChange(sale, "")
            state = awaitItem()
            assertFalse(state.canImport)
            assertTrue(state.switchSaleRowSkipped(state.rows[1]))

            vm.onSharesTextChange(sale, "22,716")
            state = awaitItem()
            assertTrue(state.canImport)
            assertFalse(state.switchSaleRowSkipped(state.rows[1]))
            cancelAndIgnoreRemainingEvents()
        }
    }
}
