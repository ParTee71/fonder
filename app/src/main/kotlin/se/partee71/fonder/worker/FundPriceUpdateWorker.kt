package se.partee71.fonder.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository
import java.time.LocalDate

/** Uppdaterar kurser dagligen för alla fonder användaren bevakar (se issue #3). */
@HiltWorker
class FundPriceUpdateWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result =
        if (refreshAll(transactionRepository, fundPriceRepository)) Result.success() else Result.retry()

    companion object {
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
         * @return true om det inte finns några bevakade fonder, eller om minst en fonds
         *   uppdatering lyckades — false bara om samtliga fonder misslyckades (troligen ett
         *   tillfälligt nätverksfel), så [CoroutineWorker.Result.retry] kan användas i stället
         *   för att vänta ett helt dygn på nästa körning.
         */
        internal suspend fun refreshAll(
            transactionRepository: TransactionRepository,
            fundPriceRepository: FundPriceRepository,
        ): Boolean {
            val funds = transactionRepository.observeFunds().first()
            if (funds.isEmpty()) return true

            val earliestPurchaseByFund = transactionRepository.observeTransactions().first()
                .groupBy { it.fundId }
                .mapValues { (_, txs) -> LocalDate.ofEpochDay(txs.minOf { it.epochDay }) }

            var anySuccess = false
            funds.forEach { fund ->
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
