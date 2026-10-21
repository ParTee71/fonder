package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundTag
import se.partee71.fonder.domain.model.Holding

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
