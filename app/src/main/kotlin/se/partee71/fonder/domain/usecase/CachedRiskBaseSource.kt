package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository

/**
 * Riskkortets portföljdel ([PortfolioRiskSource.RiskBase]) för skärmar som **bara läser cachen** —
 * Beslut (NAV-9, issue #240). Samma beräkning som Hem gör i sitt eget flöde
 * ([PortfolioRiskSource.base] på [PortfolioExposureCalc.Result.byRiskLevel]), men metadatan slås upp
 * med [FundMetadataRepository.cachedMetadataFor] och aldrig mot nätet: Beslut hämtar ingenting när
 * fliken öppnas, och flikens märke får inte kunna utlösa en hämtning. Det Hem eller
 * bakgrundskörningen redan cachat räcker — saknas en fonds nivå räknas den inte in, som på Hem.
 *
 * Emitterar null utan profil (då finns inget kort).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CachedRiskBaseSource @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val fundPriceRepository: FundPriceRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val portfolioRiskSource: PortfolioRiskSource
) {

    fun observe(): Flow<PortfolioRiskSource.RiskBase?> = combine(transactionRepository.observeFunds(), transactionRepository.observeTransactions()) { funds, transactions ->
        funds to transactions
    }.flatMapLatest { (funds, transactions) ->
        val holdings = PortfolioCalc.computeHoldings(funds, transactions)
        combine(fundPriceRepository.observeLatestPrices(holdings.map { it.fund.fundId }), portfolioRiskSource.observeContext()) { prices, context ->
            val enriched = PortfolioCalc.withCurrentValue(holdings, prices)
            val metadata = fundMetadataRepository.cachedMetadataFor(enriched.mapNotNull { it.fund.isin })
            val exposure = PortfolioExposureCalc.compute(enriched, metadata)
            portfolioRiskSource.base(context, funds, transactions, exposure.byRiskLevel, metadata, LocalDate.now())
        }
    }.distinctUntilChanged()
}
