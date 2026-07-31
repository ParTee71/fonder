package se.partee71.fonder.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.data.repository.isPriceStale
import se.partee71.fonder.domain.usecase.FundMetadataFreshness
import se.partee71.fonder.domain.usecase.PortfolioCalc
import java.time.LocalDate

/**
 * Uppdaterar kurser för alla fonder användaren bevakar (se issue #3), handelsdagsmedvetet sedan
 * issue #27/TP-17 — koalescerad av [se.partee71.fonder.worker.FundPriceRefreshScheduler] mellan
 * appstart (launch-gate), en gles periodisk backstop och en manuell "Uppdatera nu"-knapp.
 *
 * Fyller sedan issue #61 även på den persisterade billigare-alternativ-jämförelsen (ANA-9/HEM-6)
 * inkrementellt, [KEY_SCAN_COMPARISONS] satt — se [scanComparisons]. Ingen ny worker eller
 * schemaläggare: den här itererar redan alla bevakade fonder på ett schema som redan är
 * koalescerat, så jämförelsen rider med i stället för att duplicera den mekaniken.
 */
@HiltWorker
class FundPriceUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val preferencesRepository: PreferencesRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val success = refreshAll(transactionRepository, fundPriceRepository, force)
        if (success) preferencesRepository.setLastPriceSyncEpochMillis(System.currentTimeMillis())

        if (inputData.getBoolean(KEY_SCAN_COMPARISONS, false)) {
            scanComparisons(transactionRepository, fundPriceRepository, fundMetadataRepository)
        }

        return if (success) Result.success() else Result.retry()
    }

    companion object {
        /** Input-data-nyckel för att forcera en uppdatering, bypassar staleness-gaten (manuell knapp, SET-2). */
        const val KEY_FORCE = "force"

        /**
         * Input-data-nyckel som slår på [scanComparisons] — satt **bara** av
         * [FundPriceRefreshScheduler.scheduleBackstop] (var 12:e timme), aldrig av launch-gaten
         * eller den manuella "Uppdatera nu"-knappen. En jämförelseskanning kan kosta upp till
         * ~50 hämtningar per innehav (ISIN-uppslag + kandidatfråga + köpbarhetsverifiering) —
         * att lägga den på launch-gaten hade gjort appstart dyr, exakt det HEM-5 (#60) redan
         * vägrade göra.
         */
        const val KEY_SCAN_COMPARISONS = "scan_comparisons"

        /**
         * Högst så här många innehav jämförs per körning — inkrementell ifyllnad i stället för
         * en engångsskanning, så en normalstor portfölj är genomsökt inom några dygns
         * bakgrundskörningar utan en dyr engångs-burst. Störst innehavsvärde (mest potential)
         * prioriteras, se [scanComparisons].
         */
        private const val MAX_COMPARISONS_PER_RUN = 2

        /**
         * Ren logik utan `CoroutineWorker`-beroende, så den kan enhetstestas direkt (issue:
         * kodgranskning fann att den dagliga uppdateringen aldrig hämtade kurser för
         * ISIN-matchade fonder, se KRAVLISTA TP-14 — [FundPriceRepository.refresh] nycklas på
         * Handelsbankens FundId, som ISIN-matchade fonder saknar).
         *
         * Fonder med känt ISIN ([se.partee71.fonder.domain.model.Fund.isin] != null, t.ex.
         * fonder från andra fondbolag matchade via [FundPriceRepository.findFundByIsin])
         * uppdateras via [FundPriceRepository.refreshSince] i stället för [FundPriceRepository.refresh]
         * — annars nås de aldrig av den dagliga uppdateringen (samma gren som
         * `FondDetaljViewModel`/`ImportHoldingsViewModel` redan använder).
         *
         * @param force hoppar över [isPriceStale]-gaten och uppdaterar alla bevakade fonder,
         *   oavsett om cachen redan är aktuell (TP-17, den manuella "Uppdatera nu"-knappen, SET-2).
         *   Annars uppdateras bara fonder vars cachade kurs faktiskt är inaktuell — gör den
         *   periodiska backstopen billig (inget nätverksanrop när kursen redan är färsk).
         * @return true om det inte finns några bevakade fonder, om ingen fond var inaktuell, eller
         *   om minst en fonds uppdatering lyckades — false bara om samtliga (inaktuella) fonder
         *   misslyckades (troligen ett tillfälligt nätverksfel), så [CoroutineWorker.Result.retry]
         *   kan användas i stället för att vänta på nästa schemalagda körning.
         */
        internal suspend fun refreshAll(
            transactionRepository: TransactionRepository,
            fundPriceRepository: FundPriceRepository,
            force: Boolean = false,
        ): Boolean {
            val funds = transactionRepository.observeFunds().first()
            if (funds.isEmpty()) return true

            val targets = if (force) funds else funds.filter { fundPriceRepository.isPriceStale(it.fundId) }
            if (targets.isEmpty()) return true

            val earliestPurchaseByFund = transactionRepository.observeTransactions().first()
                .groupBy { it.fundId }
                .mapValues { (_, txs) -> LocalDate.ofEpochDay(txs.minOf { it.epochDay }) }

            var anySuccess = false
            targets.forEach { fund ->
                val isin = fund.isin
                // Utan köphistorik finns ingen historikhorisont att backfilla mot — en bevakad
                // men aldrig köpt fond behöver bara en färsk kurs, inte trettio år bakåt
                // (TP-18). Repositoryt hämtar då bara sitt korta fönster.
                val since = earliestPurchaseByFund[fund.fundId]
                val success = if (isin != null && since != null) {
                    fundPriceRepository.refreshSince(fund.fundId, isin, since)
                } else {
                    fundPriceRepository.refresh(fund.fundId, since)
                }
                if (success) anySuccess = true
            }
            return anySuccess
        }

        /**
         * Fyller inkrementellt på den persisterade billigare-alternativ-jämförelsen (ANA-9/HEM-6,
         * issue #61) — högst [MAX_COMPARISONS_PER_RUN] innehav per körning, störst nuvarande
         * värde (mest potential) först. Innehav utan ISIN kan aldrig jämföras (samma princip som
         * [PortfolioFeeCalc.compute]s `unknownFeeCount`) och utelämnas. Ren logik utan
         * `CoroutineWorker`-beroende, samma mönster som [refreshAll].
         *
         * [se.partee71.fonder.data.repository.FundMetadataRepository.suggestCheaperAlternatives]
         * gör själva jämförelsen (och skriver resultatet) och degraderar redan tyst vid nätverksfel
         * — inget här behöver fånga fel för att undvika en krascha-worker.
         */
        internal suspend fun scanComparisons(
            transactionRepository: TransactionRepository,
            fundPriceRepository: FundPriceRepository,
            fundMetadataRepository: FundMetadataRepository,
            today: LocalDate = LocalDate.now(),
        ) {
            val funds = transactionRepository.observeFunds().first().filter { it.isin != null }
            if (funds.isEmpty()) return

            val holdings = PortfolioCalc.computeHoldings(funds, transactionRepository.observeTransactions().first())
            if (holdings.isEmpty()) return

            val prices = fundPriceRepository.observeLatestPrices(holdings.map { it.fund.fundId }).first()
            val withValue = PortfolioCalc.withCurrentValue(holdings, prices)
            val metadataByIsin = fundMetadataRepository.metadataFor(holdings.mapNotNull { it.fund.isin })

            val targets = withValue
                .filter { it.fund.isin != null && it.currentValue != null }
                .filter { holding ->
                    val resolvedAt = metadataByIsin[holding.fund.isin]?.comparisonResolvedAtEpochDay
                    resolvedAt == null || FundMetadataFreshness.isStale(resolvedAt, today, FundMetadataFreshness.COMPARISON_TTL_DAYS)
                }
                .sortedByDescending { it.currentValue }
                .take(MAX_COMPARISONS_PER_RUN)

            targets.forEach { holding ->
                fundMetadataRepository.suggestCheaperAlternatives(holding.fund.isin!!, holding.currentValue!!)
            }
        }
    }
}
