package se.partee71.fonder.ui.hem

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.PortfolioCalc
import se.partee71.fonder.domain.usecase.PortfolioExposureCalc
import se.partee71.fonder.domain.usecase.PortfolioFeeCalc
import se.partee71.fonder.domain.usecase.PortfolioPerformanceCalc
import se.partee71.fonder.domain.usecase.PortfolioRiskCalc
import java.time.LocalDate
import javax.inject.Inject

/** Ett innehav flaggat gul eller röd i analysen (HEM-4), med det underliggande resultatet för visning av triggade signaler. */
data class FlaggedHolding(val fund: Fund, val analysis: FundAnalysisCalc.Analysis)

/**
 * Ett enskilt föreslaget byte i bytesplanen, redo för visning (HEM-8, issue #70) —
 * härlett ur redan inspelade [SuggestionRecord] (se [HemViewModel.buildSwitchPlan]), inte
 * omräknat live: samma nätverkskostnadsskäl som gör att facit-inspelningen själv ligger på
 * bakgrundsworkerns backstop, inte på varje Hem-öppning.
 */
data class SwitchSuggestionUi(
    val sellFundName: String,
    val buyFundName: String,
    val fromLevel: Int,
    val toLevel: Int,
    val feeDeltaPercent: Double,
)

/** Summering av [FundAnalysisCalc]-status över alla innehav (issue #16, HEM-4). */
data class AnalysisSummary(
    val gronCount: Int = 0,
    val gulCount: Int = 0,
    val rodCount: Int = 0,
    /** Bara gul/röd, sorterat mest allvarligt (röd) först — se [HemScreen]. */
    val flagged: List<FlaggedHolding> = emptyList(),
)

data class HemUiState(
    val loading: Boolean = true,
    val hasHoldings: Boolean = false,
    val totalInvested: Double = 0.0,
    val totalValue: Double = 0.0,
    val totalGainLoss: Double = 0.0,
    val totalGainLossFraction: Double? = null,
    val performance: PortfolioPerformanceCalc.PortfolioPerformance = PortfolioPerformanceCalc.PortfolioPerformance(
        day = PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
        week = PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
        month = PortfolioPerformanceCalc.PortfolioPeriodResult.InsufficientHistory,
    ),
    val analysisSummary: AnalysisSummary = AnalysisSummary(),
    /** Äldsta NAV-datumet bland innehav med känt värde, för "per <datum>" bredvid totalen (POR-7, issue #27). */
    val navEpochDay: Long? = null,
    /** Portföljens totala fondavgift per år (HEM-5, issue #60). */
    val feeSummary: PortfolioFeeCalc.Result = PortfolioFeeCalc.Result(totalAnnualFeeKr = 0.0, byHolding = emptyList(), unknownFeeCount = 0),
    /** Riskprofilens målnivå (SET-3), null om ingen profil är satt — raden uteblir helt då (HEM-7, issue #68). */
    val riskProfile: RiskProfile? = null,
    /** Innehavens genomsnittliga risknivå, viktad på värde (HEM-7, issue #68). */
    val portfolioRisk: PortfolioRiskCalc.Result = PortfolioRiskCalc.Result(weightedAverageRisk = null, includedValueKr = 0.0, excludedCount = 0),
    /** Målfördelning mot innehavens faktiska fördelning, per risknivå — tom om ingen profil är satt (HEM-7, issue #71). */
    val riskLevelDeviations: List<PortfolioRiskCalc.LevelDeviation> = emptyList(),
    /** Rangordnad bytesplan mot målfördelningen (HEM-8, issue #70) — tom utom i ISK/KF med en avvikelse som passerat [se.partee71.fonder.domain.usecase.SwitchPlanCalc.MIN_GAP_PP]. */
    val switchPlan: List<SwitchSuggestionUi> = emptyList(),
) {
    val isEmpty: Boolean get() = !loading && !hasHoldings
}

/** Hur långt tillbaka ett innehavs kurshistorik hämtas för analysen (issue #16) om inget köp finns (bör inte hända för ett verkligt innehav, men skyddar mot en tom historik-hämtning). */
private const val ANALYSIS_FALLBACK_LOOKBACK_YEARS = 1L

/**
 * Hem — ny startskärm (issue #14) med portföljens totala värde/vinst/procent (samma
 * beräkning som Portfölj, [PortfolioCalc]) plus dag/vecka/månads-förändring
 * ([PortfolioPerformanceCalc]) och en analys-summering av gul-/rödflaggade fonder
 * ([FundAnalysisCalc], issue #16, HEM-4). Ingen egen "uppdatera nyss tillagd fond"-logik
 * behövs här (jämför [se.partee71.fonder.ui.portfolj.PortfoljViewModel]) — fonder läggs bara
 * till via Portfölj-fliken (NAV-3), som redan äger den engångsuppdateringen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HemViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val preferencesRepository: PreferencesRepository,
    private val suggestionRecordRepository: SuggestionRecordRepository,
) : ViewModel() {

    private val baseHoldings: Flow<Pair<List<Holding>, List<Transaction>>> =
        combine(transactionRepository.observeFunds(), transactionRepository.observeTransactions()) { funds, transactions ->
            PortfolioCalc.computeHoldings(funds, transactions) to transactions
        }

    val uiState: StateFlow<HemUiState> =
        baseHoldings.flatMapLatest { (holdings, transactions) ->
            val fundIds = holdings.map { it.fund.fundId }
            fundPriceRepository.observeLatestPrices(fundIds).map { prices ->
                val enriched = PortfolioCalc.withCurrentValue(holdings, prices)
                val today = LocalDate.now()
                val historyByFundId = enriched.associate { holding ->
                    holding.fund.fundId to fundPriceRepository.priceHistory(
                        fundId = holding.fund.fundId,
                        fromEpochDay = today.minusDays(PortfolioPerformanceCalc.HISTORY_LOOKBACK_DAYS).toEpochDay(),
                        toEpochDay = today.toEpochDay(),
                    )
                }
                val isins = enriched.mapNotNull { it.fund.isin }
                val metadataByIsin = fundMetadataRepository.metadataFor(isins)
                val riskProfile = preferencesRepository.riskProfile.first()
                val exposure = PortfolioExposureCalc.compute(enriched, metadataByIsin)
                val accountType = preferencesRepository.accountType.first()
                val switchPlan = buildSwitchPlan(accountType, metadataByIsin)
                HemUiState(
                    loading = false,
                    hasHoldings = enriched.isNotEmpty(),
                    totalInvested = PortfolioCalc.totalInvested(enriched),
                    totalValue = PortfolioCalc.totalValue(enriched),
                    totalGainLoss = PortfolioCalc.totalGainLoss(enriched),
                    totalGainLossFraction = PortfolioCalc.totalGainLossFraction(enriched),
                    performance = PortfolioPerformanceCalc.totalPerformance(enriched, today, historyByFundId),
                    analysisSummary = buildAnalysisSummary(enriched, transactions, today),
                    navEpochDay = PortfolioCalc.oldestKnownNavEpochDay(enriched),
                    feeSummary = PortfolioFeeCalc.compute(enriched, metadataByIsin, today),
                    riskProfile = riskProfile,
                    portfolioRisk = PortfolioRiskCalc.compute(enriched, metadataByIsin),
                    riskLevelDeviations = riskProfile?.let {
                        val actualAllocation = exposure.byRiskLevel.buckets.associate { bucket -> bucket.label.toInt() to bucket.fraction }
                        PortfolioRiskCalc.deviationByLevel(it.effectiveAllocation, actualAllocation)
                    }.orEmpty(),
                    switchPlan = switchPlan,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HemUiState(),
        )

    /**
     * Analyserar varje innehav och summerar statusarna (HEM-4). Övrig kurshistorik för
     * varje innehav hämtas sedan första köpet ur den lokala cachen (Room), samma räckvidd
     * som [se.partee71.fonder.ui.fond.FondDetaljViewModel] använder för sin egen fond —
     * ingen ny nätverksuppdatering.
     */
    private suspend fun buildAnalysisSummary(
        enriched: List<Holding>,
        transactions: List<Transaction>,
        today: LocalDate,
    ): AnalysisSummary {
        if (enriched.isEmpty()) return AnalysisSummary()

        val firstPurchaseByFund = transactions
            .groupBy { it.fundId }
            .mapValues { (_, txs) -> LocalDate.ofEpochDay(txs.minOf { it.epochDay }) }
        val portfolioTotalValue = PortfolioCalc.totalValue(enriched)

        val historyByFundId = enriched.associate { holding ->
            val since = firstPurchaseByFund[holding.fund.fundId] ?: today.minusYears(ANALYSIS_FALLBACK_LOOKBACK_YEARS)
            holding.fund.fundId to fundPriceRepository.priceHistory(holding.fund.fundId, since.toEpochDay(), today.toEpochDay())
        }

        val analyses = enriched.mapNotNull { holding ->
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
            FlaggedHolding(holding.fund, analysis)
        }

        return AnalysisSummary(
            gronCount = analyses.count { it.analysis.status == FundAnalysisCalc.SignalLevel.GRON },
            gulCount = analyses.count { it.analysis.status == FundAnalysisCalc.SignalLevel.GUL },
            rodCount = analyses.count { it.analysis.status == FundAnalysisCalc.SignalLevel.ROD },
            flagged = analyses
                .filter { it.analysis.status == FundAnalysisCalc.SignalLevel.GUL || it.analysis.status == FundAnalysisCalc.SignalLevel.ROD }
                .sortedByDescending { it.analysis.status == FundAnalysisCalc.SignalLevel.ROD },
        )
    }

    /**
     * Läser den senast inspelade bytesplanen ur facit ([SuggestionRecordRepository], HEM-8,
     * issue #70) för visning — räknar **inte** om planen live, se [SwitchSuggestionUi]s KDoc.
     * Tom lista utan ISK/KF (SET-4) eller innan bakgrundsworkerns backstop hunnit spela in
     * något än — appen väntar hellre än att gissa.
     */
    private suspend fun buildSwitchPlan(accountType: AccountType?, heldMetadataByIsin: Map<String, FundMetadata>): List<SwitchSuggestionUi> {
        if (accountType != AccountType.ISK_KF) return emptyList()
        val records = suggestionRecordRepository.observeAll().first()
        if (records.isEmpty()) return emptyList()

        val latestDay = records.maxOf { it.suggestedAtEpochDay }
        val latestBatch = records.filter { it.suggestedAtEpochDay == latestDay }.sortedBy { it.planIndex }
        val buyMetadataByIsin = fundMetadataRepository.metadataFor(latestBatch.map { it.buyIsin }.distinct())

        return latestBatch.mapNotNull { record ->
            val sellMeta = heldMetadataByIsin[record.sellIsin] ?: return@mapNotNull null
            val buyMeta = buyMetadataByIsin[record.buyIsin] ?: return@mapNotNull null
            val sellLevel = sellMeta.risk ?: return@mapNotNull null
            val buyLevel = buyMeta.risk ?: return@mapNotNull null
            val sellFee = sellMeta.totalFee ?: return@mapNotNull null
            val buyFee = buyMeta.totalFee ?: return@mapNotNull null
            SwitchSuggestionUi(
                sellFundName = sellMeta.name,
                buyFundName = buyMeta.name,
                fromLevel = sellLevel,
                toLevel = buyLevel,
                feeDeltaPercent = buyFee - sellFee,
            )
        }
    }
}
