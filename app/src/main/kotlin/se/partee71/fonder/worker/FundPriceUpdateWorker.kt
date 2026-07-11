package se.partee71.fonder.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.data.repository.isPriceStale
import java.time.LocalDate

/**
 * Uppdaterar kurser för alla fonder användaren bevakar (se issue #3), handelsdagsmedvetet sedan
 * issue #27/TP-17 — koalescerad av [se.partee71.fonder.worker.FundPriceRefreshScheduler] mellan
 * appstart (launch-gate), en gles periodisk backstop och en manuell "Uppdatera nu"-knapp.
 */
@HiltWorker
class FundPriceUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val preferencesRepository: PreferencesRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val force = inputData.getBoolean(KEY_FORCE, false)
        val success = refreshAll(transactionRepository, fundPriceRepository, force)
        if (success) preferencesRepository.setLastPriceSyncEpochMillis(System.currentTimeMillis())
        return if (success) Result.success() else Result.retry()
    }

    companion object {
        /** Input-data-nyckel för att forcera en uppdatering, bypassar staleness-gaten (manuell knapp, SET-2). */
        const val KEY_FORCE = "force"

        /**
         * Fem år tillbaka används som sökfönster för ISIN-baserade fonder utan känd
         * inköpshistorik (t.ex. bevakade men aldrig köpta) — samma fallback som
         * `ImportHoldingsViewModel`/`FondDetaljViewModel` använder när inget köpdatum finns.
         */
        private const val ISIN_FALLBACK_LOOKBACK_YEARS = 5L

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
                val success = if (isin != null) {
                    val since = earliestPurchaseByFund[fund.fundId]
                        ?: LocalDate.now().minusYears(ISIN_FALLBACK_LOOKBACK_YEARS)
                    fundPriceRepository.refreshSince(fund.fundId, isin, since)
                } else {
                    fundPriceRepository.refresh(fund.fundId)
                }
                if (success) anySuccess = true
            }
            return anySuccess
        }
    }
}
