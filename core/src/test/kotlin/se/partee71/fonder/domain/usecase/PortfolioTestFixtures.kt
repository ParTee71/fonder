package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.FundTag
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/**
 * Delade fixturer för portföljberäkningarnas tester (avgift, risk, exponering): ett innehav med
 * ett givet värde och fondmetadata med bara de fält testet bryr sig om. Fonden heter som sitt
 * id och har ISIN `SE<id>` om inget annat anges.
 */
internal fun portfolioHolding(fundId: String, currentValue: Double?, isin: String? = "SE$fundId") = Holding(
    fund = Fund(fundId = fundId, name = fundId, isin = isin),
    netShares = 1.0,
    netInvested = 0.0,
    currentValue = currentValue
)

internal fun fundMetadata(
    isin: String,
    totalFee: Double? = null,
    managementFee: Double? = totalFee,
    fundType: String? = null,
    risk: Int? = null,
    indexFund: Boolean = false,
    tags: List<FundTag> = emptyList(),
    cheapestAlternativeIsin: String? = null,
    cheapestAlternativeFee: Double? = null,
    comparisonResolvedAtEpochDay: Long? = null
) = FundMetadata(
    isin = isin, name = isin, orderbookId = isin, totalFee = totalFee, managementFee = managementFee,
    category = null, fundType = fundType, companyName = null, risk = risk, indexFund = indexFund,
    startDateEpochDay = null, minimumBuy = null, tags = tags,
    cheapestAlternativeIsin = cheapestAlternativeIsin,
    cheapestAlternativeFee = cheapestAlternativeFee,
    comparisonResolvedAtEpochDay = comparisonResolvedAtEpochDay
)

/**
 * `tools/fondmatte/periodkop.json` (issue #193): fond A köps 2026-09-01, köps igen med avgift
 * 2026-10-01 och delsäljs 2026-10-09 – köp och sälj inom periodraderna. Värderingsdag 2026-10-16.
 * Kursen på transaktionsdagarna är transaktionens kurs. Golden-värdena står i testerna.
 */
internal object PeriodkopFixture {
    val fund = Fund(fundId = "A", name = "A")
    val endDate: LocalDate = LocalDate.of(2026, 10, 16)

    private fun day(month: Int, dayOfMonth: Int) = LocalDate.of(2026, month, dayOfMonth).toEpochDay()

    val transactions = listOf(
        Transaction(fundId = "A", type = TransactionType.KOP, epochDay = day(9, 1), shares = 100.0, pricePerShare = 101.5),
        Transaction(fundId = "A", type = TransactionType.KOP, epochDay = day(10, 1), shares = 50.0, pricePerShare = 106.0, fee = 10.0),
        Transaction(fundId = "A", type = TransactionType.SALJ, epochDay = day(10, 9), shares = 30.0, pricePerShare = 105.3, fee = 10.0)
    )

    val history = listOf(
        day(7, 16) to 98.0,
        day(8, 14) to 99.2,
        day(9, 1) to 101.5,
        day(9, 16) to 102.25,
        day(10, 1) to 106.0,
        day(10, 9) to 105.3,
        day(10, 15) to 108.9,
        day(10, 16) to 110.2
    ).map { (epochDay, nav) -> FundPrice(fundId = "A", epochDay = epochDay, nav = nav) }

    /** Innehavet som appen räknar fram det: [PortfolioCalc.computeHoldings] värderat till senaste NAV. */
    val holding: Holding = PortfolioCalc.withCurrentValue(
        PortfolioCalc.computeHoldings(listOf(fund), transactions),
        mapOf("A" to history.last())
    ).single()
}
