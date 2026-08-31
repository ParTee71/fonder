package se.partee71.fonder.ui.portfolj

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.data.repository.isPriceStale
import se.partee71.fonder.data.repository.refreshFund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.PortfolioCalc
import se.partee71.fonder.domain.usecase.PortfolioExposureCalc
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import se.partee71.fonder.worker.BackgroundWork
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import java.time.LocalDate
import javax.inject.Inject

data class PortfoljUiState(
    val loading: Boolean = true,
    val holdings: List<Holding> = emptyList(),
    val totalInvested: Double = 0.0,
    val totalValue: Double = 0.0,
    val totalGainLoss: Double = 0.0,
    val totalGainLossFraction: Double? = null,
    /** Dag/vecka/månads-förändring per fond, se issue #14 (POR-5). Nyckel: `Fund.fundId`. */
    val performance: Map<String, PortfolioPerformanceCalc.HoldingPerformance> = emptyMap(),
    /** Äldsta NAV-datumet bland innehav med känt värde, för "per <datum>" bredvid totalen (POR-7, issue #27). */
    val navEpochDay: Long? = null,
    /** Säljsignal-status och ev. vinstsignal per innehav (ANA-3/ANA-8, POR-8, issue #26). Nyckel: `Fund.fundId`. */
    val analysis: Map<String, FundAnalysisCalc.Analysis> = emptyMap(),
    /** Exponeringskarta: andel per fondtyp/region/index-aktivt (POR-9, issue #66). */
    val exposure: PortfolioExposureCalc.Result = EMPTY_EXPOSURE,
    /**
     * Risknivå (1–7, TP-21) per innehav (UI-10, issue #85). Nyckel: `Fund.fundId`. En fond utan
     * känd risknivå saknas i kartan och visas som okänd — aldrig gissad (ANA-4-principen).
     * Läses ur samma [metadata]-flöde som exponeringskartan, alltså utan extra nätverksanrop.
     */
    val riskLevels: Map<String, Int> = emptyMap(),
    /** Kurserna hämtas just nu i bakgrunden ([BackgroundWork.PRICE_REFRESH]) — kortens väntesnurra (NAV-6). */
    val pricesWorking: Boolean = false,
    /** Fondmetadatan slås upp just nu (nätverk per okänd ISIN) — exponeringskartan vilar på den. */
    val metadataWorking: Boolean = false,
    /**
     * Fonder vars engångsuppdatering av kursen pågår just nu (se [PortfoljViewModel]s `init`).
     * Per fond, inte ett gemensamt "något hämtas": en nyss tillagd fond uppdateras medan resten
     * av listan står färdig, och då ska bara **den** raden snurra (NAV-6).
     */
    val refreshingFundIds: Set<String> = emptySet(),
) {
    val isEmpty: Boolean get() = !loading && holdings.isEmpty()

    // Per kort/rad, härlett här i stället för i skärmen så kopplingen är enhetstestbar utan
    // Compose (samma uppdelning som HemUiState). Den första laddningen räknas alltid som
    // pågående: då är varje siffra på skärmen en nolla som ännu inte fyllts i.
    val totalWorking: Boolean get() = loading || pricesWorking || refreshingFundIds.isNotEmpty()

    /** Exponeringskartan (POR-9) hänger på metadatan, inte på kurserna. */
    val exposureWorking: Boolean get() = loading || metadataWorking

    /** En innehavsrad snurrar när just dess kurs hämtas, eller när alla kurser uppdateras. */
    fun holdingWorking(fundId: String): Boolean = pricesWorking || fundId in refreshingFundIds
}

private val EMPTY_DIMENSION = PortfolioExposureCalc.Dimension(buckets = emptyList(), unknownValueKr = 0.0, unknownFraction = 0.0, unknownCount = 0)
private val EMPTY_EXPOSURE = PortfolioExposureCalc.Result(
    byType = EMPTY_DIMENSION,
    byRegion = EMPTY_DIMENSION,
    indexStatus = PortfolioExposureCalc.IndexStatusSplit(indexValueKr = 0.0, indexFraction = 0.0, activeValueKr = 0.0, activeFraction = 0.0),
    includedValueKr = 0.0,
    excludedCount = 0,
)

/** Hur långt tillbaka ett innehavs kurshistorik hämtas för analysen (issue #26) om inget köp finns (bör inte hända för ett verkligt innehav). Samma princip som `HemViewModel`. */
private const val ANALYSIS_FALLBACK_LOOKBACK_YEARS = 1L

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PortfoljViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val fundPriceRefreshScheduler: FundPriceRefreshScheduler,
) : ViewModel() {

    private val baseHoldings: Flow<Pair<List<Holding>, List<Transaction>>> =
        combine(transactionRepository.observeFunds(), transactionRepository.observeTransactions()) { funds, transactions ->
            PortfolioCalc.computeHoldings(funds, transactions) to transactions
        }

    /**
     * Fondmetadata i ett **eget** flöde, inte hämtad inne i tillståndets `map`.
     * [FundMetadataRepository.metadataFor] gör ett sekventiellt nätverksuppslag per ISIN som
     * saknas eller är inaktuell i cachen — låg det i `map` blockerades hela `uiState` på
     * nätverket, så vyn stod kvar i `loading` och ritade totalen som "0,00 kr · Kurs saknas"
     * så länge uppkopplingen hängde, trots att innehav och värden fanns lokalt. Nu ritas
     * portföljen direkt och exponeringskartan (POR-9) fylls i när metadatan landar.
     */
    private val metadata = MutableStateFlow<Map<String, FundMetadata>>(emptyMap())
    private var metadataJob: Job? = null
    private var metadataIsins: List<String>? = null

    /**
     * Sant medan uppslaget ovan pågår — exponeringskortets väntesnurra (NAV-6). Utan den ser en
     * ännu ofylld exponeringskarta likadan ut som en portfölj källan inte kan klassificera, och
     * de två betyder helt olika saker (ANA-4-principen).
     */
    private val metadataLoading = MutableStateFlow(false)

    /** Se [se.partee71.fonder.ui.hem.HemViewModel]s motsvarighet: bara den senaste begäran får släcka snurran. */
    private var metadataRequestId = 0

    /** Fonder vars engångsuppdatering pågår — driver radernas snurra, se [PortfoljUiState.refreshingFundIds]. */
    private val refreshingFundIds = MutableStateFlow<Set<String>>(emptySet())

    private fun refreshMetadata(isins: List<String>) {
        if (isins == metadataIsins) return
        metadataIsins = isins
        val requestId = ++metadataRequestId
        metadataJob?.cancel()
        // Utan ISIN finns inget svar på väg — då ska kortet inte snurra. En snurra som tänds och
        // släcks utan att något hämtas lär användaren att inte se den, och tillståndet emitterar
        // två gånger i onödan.
        if (isins.isEmpty()) {
            metadataLoading.value = false
            metadata.value = emptyMap()
            return
        }
        metadataLoading.value = true
        metadataJob = viewModelScope.launch {
            try {
                metadata.value = fundMetadataRepository.metadataFor(isins)
            } finally {
                if (requestId == metadataRequestId) metadataLoading.value = false
            }
        }
    }

    /** Körstatusen samlad, så `uiState`-combinen växer med ett led i stället för tre. */
    private data class WorkState(
        val running: Set<BackgroundWork>,
        val metadataPending: Boolean,
        val refreshingFundIds: Set<String>,
    )

    private val workState: Flow<WorkState> =
        combine(
            fundPriceRefreshScheduler.observeRunningWork(),
            metadataLoading,
            refreshingFundIds,
        ) { running, metadataPending, refreshing ->
            WorkState(running, metadataPending, refreshing)
        }

    val uiState: StateFlow<PortfoljUiState> =
        baseHoldings.flatMapLatest { (holdings, transactions) ->
            val fundIds = holdings.map { it.fund.fundId }
            combine(fundPriceRepository.observeLatestPrices(fundIds), metadata) { prices, metadataByIsin ->
                val enriched = PortfolioCalc.withCurrentValue(holdings, prices)
                val today = LocalDate.now()
                val performance = enriched.associate { holding ->
                    val history = fundPriceRepository.priceHistory(
                        fundId = holding.fund.fundId,
                        fromEpochDay = today.minusDays(PortfolioPerformanceCalc.HISTORY_LOOKBACK_DAYS).toEpochDay(),
                        toEpochDay = today.toEpochDay(),
                    )
                    holding.fund.fundId to PortfolioPerformanceCalc.holdingPerformance(holding, today, history)
                }
                refreshMetadata(enriched.mapNotNull { it.fund.isin })
                PortfoljUiState(
                    loading = false,
                    holdings = enriched,
                    totalInvested = PortfolioCalc.totalInvested(enriched),
                    totalValue = PortfolioCalc.totalValue(enriched),
                    totalGainLoss = PortfolioCalc.totalGainLoss(enriched),
                    totalGainLossFraction = PortfolioCalc.totalGainLossFraction(enriched),
                    performance = performance,
                    navEpochDay = PortfolioCalc.oldestKnownNavEpochDay(enriched),
                    analysis = buildAnalysis(enriched, transactions, today),
                    exposure = PortfolioExposureCalc.compute(enriched, metadataByIsin),
                    riskLevels = enriched.mapNotNull { holding ->
                        val risk = holding.fund.isin?.let { metadataByIsin[it]?.risk } ?: return@mapNotNull null
                        holding.fund.fundId to risk
                    }.toMap(),
                )
            }
        }.combine(workState) { state, work ->
            // Eget flöde, inte en gren i combinen ovan: körstatusen kommer från WorkManager och
            // från ViewModel:ens egna jobb — den ska inte kunna räkna om portföljen bara för att
            // ett jobb startade (samma gräns som HemViewModel drar).
            state.copy(
                pricesWorking = BackgroundWork.PRICE_REFRESH in work.running,
                metadataWorking = work.metadataPending,
                refreshingFundIds = work.refreshingFundIds,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = PortfoljUiState(),
        )

    /**
     * Analyserar varje innehav (ANA-3/ANA-8, POR-8, issue #26) — samma princip som
     * [se.partee71.fonder.ui.hem.HemViewModel.buildAnalysisSummary], men returnerar hela
     * analysen per innehav i stället för en summering, eftersom Portfölj visar signalen på
     * varje enskild rad. Övrig kurshistorik hämtas ur den lokala cachen (Room), ingen ny
     * nätverksuppdatering.
     */
    private suspend fun buildAnalysis(
        enriched: List<Holding>,
        transactions: List<Transaction>,
        today: LocalDate,
    ): Map<String, FundAnalysisCalc.Analysis> {
        if (enriched.isEmpty()) return emptyMap()

        val firstPurchaseByFund = transactions
            .groupBy { it.fundId }
            .mapValues { (_, txs) -> LocalDate.ofEpochDay(txs.minOf { it.epochDay }) }
        val portfolioTotalValue = PortfolioCalc.totalValue(enriched)

        val historyByFundId = enriched.associate { holding ->
            val since = firstPurchaseByFund[holding.fund.fundId] ?: today.minusYears(ANALYSIS_FALLBACK_LOOKBACK_YEARS)
            holding.fund.fundId to fundPriceRepository.priceHistory(holding.fund.fundId, since.toEpochDay(), today.toEpochDay())
        }

        return enriched.mapNotNull { holding ->
            val firstPurchase = firstPurchaseByFund[holding.fund.fundId] ?: return@mapNotNull null
            val otherHistories = historyByFundId.filterKeys { it != holding.fund.fundId }
            val analysis = FundAnalysisCalc.analyze(
                today = today,
                holding = holding,
                priceHistory = historyByFundId[holding.fund.fundId].orEmpty(),
                firstPurchaseDate = firstPurchase,
                portfolioTotalValue = portfolioTotalValue,
                otherHoldingsAverageThreeMonthReturn = FundAnalysisCalc.averageThreeMonthReturn(today, otherHistories),
            ) ?: return@mapNotNull null
            holding.fund.fundId to analysis
        }.toMap()
    }

    /**
     * Dra ned för att uppdatera (UI-11) — samma forcerade kursuppdatering som Hem begär, och
     * samma unika arbetsnamn, så en dragning på den ena skärmen och en på den andra aldrig blir
     * två körningar.
     */
    fun refresh() {
        fundPriceRefreshScheduler.triggerManualRefresh()
    }

    // Engångsuppdatering per fond utan cachad kurs, eller vars cachade kurs är inaktuell
    // (TP-17 — så dag/vecka/månad räknas på så färsk NAV som källan har, utan att vänta på
    // nästa dagliga WorkManager-körning). Håll enkel: ett refresh-anrop per fund och
    // ViewModel-livstid, inte en ny bakgrundsjobb-mekanism (se issue #6).
    private val refreshedFundIds = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            transactionRepository.observeFunds().collect { funds ->
                funds.forEach { fund ->
                    if (refreshedFundIds.add(fund.fundId) && fundPriceRepository.isPriceStale(fund.fundId)) {
                        // Raden märks som pågående medan hämtningen kör (NAV-6) — `finally`, så
                        // en fond vars källa svarar med fel inte blir stående med en snurra som
                        // aldrig slutar.
                        refreshingFundIds.update { it + fund.fundId }
                        try {
                            // Utan köphistorik finns ingen historikhorisont att hämta mot — då
                            // räcker repositoryts korta färska fönster (TP-18), se worker-varianten.
                            val since = transactionRepository.observeTransactionsForFund(fund.fundId).first()
                                .minOfOrNull { it.epochDay }
                                ?.let(LocalDate::ofEpochDay)
                            if (since != null) {
                                fundPriceRepository.refreshFund(fund, since)
                            } else {
                                fundPriceRepository.refresh(fund.fundId)
                            }
                        } finally {
                            refreshingFundIds.update { it - fund.fundId }
                        }
                    }
                }
            }
        }
    }
}
