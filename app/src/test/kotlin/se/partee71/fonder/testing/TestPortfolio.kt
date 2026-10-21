package se.partee71.fonder.testing

import app.cash.turbine.ReceiveTurbine
import java.time.LocalDate
import se.partee71.fonder.data.repository.FakeCachedPriceRepository
import se.partee71.fonder.data.repository.FakeTransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.FundTag
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/*
 * Delade fixturer för tester som bygger en portfölj (Hem, Portfölj, Fonddetalj …): köp, kurser
 * och fondmetadata med bara de fält testet bryr sig om, så att varje test inte skriver av samma
 * konstruktoranrop (regel 4, cpdCheck).
 */

/** Ett köp av [shares] andelar à [pricePerShare] kr i [fund] den [day]. */
fun buy(fund: Fund, day: LocalDate, shares: Double, pricePerShare: Double) = Transaction(
    fundId = fund.fundId,
    type = TransactionType.KOP,
    epochDay = day.toEpochDay(),
    shares = shares,
    pricePerShare = pricePerShare
)

/** Kursen [nav] för [fund] den [day]. */
fun price(fund: Fund, day: LocalDate, nav: Double) = FundPrice(fundId = fund.fundId, epochDay = day.toEpochDay(), nav = nav)

/** Fondmetadata där allt testet inte anger är okänt; namn och orderbok-id är ISIN:et om inget annat sägs. */
fun testFundMetadata(
    isin: String,
    name: String = isin,
    orderbookId: String = isin,
    totalFee: Double? = null,
    managementFee: Double? = null,
    category: String? = null,
    fundType: String? = null,
    companyName: String? = null,
    risk: Int? = null,
    indexFund: Boolean = false,
    startDateEpochDay: Long? = null,
    minimumBuy: Double? = null,
    tags: List<FundTag> = emptyList(),
    availableAtHandelsbanken: Boolean? = null,
    cheapestAlternativeIsin: String? = null,
    cheapestAlternativeFee: Double? = null,
    shownAlternativeIsins: List<String> = emptyList(),
    comparisonResolvedAtEpochDay: Long? = null,
    developmentOneYear: Double? = null
) = FundMetadata(
    isin = isin, name = name, orderbookId = orderbookId, totalFee = totalFee, managementFee = managementFee,
    category = category, fundType = fundType, companyName = companyName, risk = risk, indexFund = indexFund,
    startDateEpochDay = startDateEpochDay, minimumBuy = minimumBuy, tags = tags,
    availableAtHandelsbanken = availableAtHandelsbanken, cheapestAlternativeIsin = cheapestAlternativeIsin,
    cheapestAlternativeFee = cheapestAlternativeFee, shownAlternativeIsins = shownAlternativeIsins,
    comparisonResolvedAtEpochDay = comparisonResolvedAtEpochDay, developmentOneYear = developmentOneYear
)

/** Väntar förbi tillstånden där [skip] gäller (t.ex. `{ it.loading }`) och returnerar det första där det inte gör det. */
suspend fun <T> ReceiveTurbine<T>.awaitItemSkipping(skip: (T) -> Boolean): T {
    var item = awaitItem()
    while (skip(item)) item = awaitItem()
    return item
}

/** Portföljen består av bara [fund], köpt [boughtAt] — som standard 10 andelar à 100 kr (1 000 kr investerat). */
fun FakeTransactionRepository.holdOnly(fund: Fund, boughtAt: LocalDate, shares: Double = 10.0, pricePerShare: Double = 100.0) {
    funds.value = listOf(fund)
    transactions.value = listOf(buy(fund, boughtAt, shares, pricePerShare))
}

/** Kurscachen har bara [fund]:s kurs [nav] den [day]. */
fun FakeCachedPriceRepository.quoteOnly(fund: Fund, day: LocalDate, nav: Double) {
    latestPrices.value = mapOf(fund.fundId to price(fund, day, nav))
}

/**
 * Standardinnehavet i Hem- och Portföljtesterna: "Fond A" (SHB0000442, ISIN [isin]) köpt för ett år
 * sedan, 10 andelar à 100 kr, med dagens kurs 120 kr — värd 1 200 kr, 200 kr i vinst.
 */
fun holdFundAWorth1200(
    transactions: FakeTransactionRepository,
    prices: FakeCachedPriceRepository,
    today: LocalDate,
    isin: String? = "SE0001466368"
): Fund {
    val fund = Fund(fundId = "SHB0000442", name = "Fond A", isin = isin)
    transactions.holdOnly(fund, today.minusYears(1))
    prices.quoteOnly(fund, today, 120.0)
    return fund
}
