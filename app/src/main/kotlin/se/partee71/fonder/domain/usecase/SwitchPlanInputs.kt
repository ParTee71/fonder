package se.partee71.fonder.domain.usecase

import kotlinx.coroutines.flow.first
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction
import java.time.LocalDate

/**
 * Portföljläget bytesplanens nivåer räknas på — innehaven med värde ur kurscachen, deras
 * metadata och likviden som väntar på köp per målnivå (BYT-3). **En** läsväg för bytesplanen
 * (HEM-8, bakgrundskörningen) och målnivåns förval (issue #150, [TargetLevelPreselection]), så
 * förvalet och planen aldrig mäter gapen på olika sätt.
 *
 * Metadatan slås upp av anroparen ([load]s `metadataFor`): planen får gå till källan
 * (`FundMetadataRepository.metadataFor`), medan ett sälj som registreras bara läser cachen och
 * aldrig väntar på nätet (SLD-5).
 */
class SwitchPlanInputs(
    val funds: List<Fund>,
    val transactions: List<Transaction>,
    /** Innehaven med aktuellt värde ([PortfolioCalc.withCurrentValue]) — tom när portföljen är det. */
    val holdings: List<Holding>,
    val prices: Map<String, FundPrice>,
    val metadataByIsin: Map<String, FundMetadata>,
    val pendingProceedsByLevel: Map<Int, Double>,
) {
    val heldIsins: Set<String> get() = holdings.mapNotNullTo(mutableSetOf()) { it.fund.isin }

    companion object {
        /**
         * Läser läget. Likviden räknas ur **alla** bevakningar utom [excludeWatchId] — den bevakning
         * vars målnivå just ska väljas räknas inte på någon nivå ([SwitchPlanCalc.preselectTargetLevel]).
         */
        suspend fun load(
            transactionRepository: TransactionRepository,
            fundPriceRepository: FundPriceRepository,
            switchWatchRepository: SwitchWatchRepository,
            suggestionRecordRepository: SuggestionRecordRepository,
            today: LocalDate,
            metadataFor: suspend (List<String>) -> Map<String, FundMetadata>,
            excludeWatchId: Long? = null,
        ): SwitchPlanInputs {
            val funds = transactionRepository.observeFunds().first()
            if (funds.isEmpty()) return SwitchPlanInputs(funds, emptyList(), emptyList(), emptyMap(), emptyMap(), emptyMap())
            val transactions = transactionRepository.observeTransactions().first()
            val holdings = PortfolioCalc.computeHoldings(funds, transactions)
            val prices = if (holdings.isEmpty()) emptyMap() else fundPriceRepository.observeLatestPrices(holdings.map { it.fund.fundId }).first()
            val withValue = PortfolioCalc.withCurrentValue(holdings, prices)
            val heldIsins = withValue.mapNotNull { it.fund.isin }.distinct()
            val metadataByIsin = if (heldIsins.isEmpty()) emptyMap() else metadataFor(heldIsins)

            // Alla bevakningar (de stängdas köp får inte förbrukas igen), de öppnas källförslags
            // köpfond (riktat uppslag) och innehavens redan lästa metadata.
            val watches = switchWatchRepository.getAll().filterNot { it.id == excludeWatchId }
            val buyIsinByRecordId = suggestionRecordRepository
                .observeByIds(watches.filter { it.isOpen }.mapNotNullTo(mutableSetOf()) { it.sourceRecordId }).first()
                .associate { it.id to it.buyIsin }
            val pending = SwitchPlanCalc.pendingProceedsByLevel(watches, transactions, funds, buyIsinByRecordId, metadataByIsin, today)
            return SwitchPlanInputs(funds, transactions, withValue, prices, metadataByIsin, pending)
        }
    }
}
