package se.partee71.fonder.testing

import app.cash.turbine.ReceiveTurbine
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import se.partee71.fonder.data.repository.FakeCachedPriceRepository
import se.partee71.fonder.data.repository.FakeTransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.FundTag
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.FundAnalysisCalc

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

/**
 * Fonden köpt för två år sedan, **helt såld** för ett år sedan och köpt igen för 100 dagar sedan
 * (à 105 kr) — positionen är alltså 100 dagar gammal fast fondens historik är två år (issue #194).
 */
fun FakeTransactionRepository.holdRebought(fund: Fund, today: LocalDate) {
    funds.value = listOf(fund)
    transactions.value = listOf(
        buy(fund, today.minusYears(2), 10.0, 136.5),
        Transaction(fundId = fund.fundId, type = TransactionType.SALJ, epochDay = today.minusYears(1).toEpochDay(), shares = 10.0, pricePerShare = 118.25),
        buy(fund, today.minusDays(100), 10.0, 105.0)
    )
}

/**
 * Två års fallande kurshistorik var femte dag: NAV var högre förr (100 + dagar sedan × 0,05), så
 * dagens kurs 100 hamnar under både 52-veckorstoppen och 200-dagarssnittet — samma fixturprincip
 * som FundAnalysisCalcTest. Kursen 100 dagar sedan är 105.
 */
fun decliningPriceHistory(fund: Fund, today: LocalDate): List<FundPrice> = (0..730L step 5).map { daysAgo -> price(fund, today.minusDays(daysAgo), 100.0 + daysAgo * 0.05) }

/**
 * Fonden köpt för [RECENT_PURCHASE_DAYS] dagar sedan (10 andelar à 104,5 kr, kursen den dagen) med
 * hela [decliningPriceHistory] — två år — i kurscachen och dagens kurs 100 (issue #214): historiken
 * når långt före köpet, så S1/S2 kan räknas medan "sedan köp" och kronorna utgår från köpet.
 */
fun holdRecentWithLongHistory(transactions: FakeTransactionRepository, prices: FakeCachedPriceRepository, fund: Fund, today: LocalDate) {
    transactions.holdOnly(fund, today.minusDays(RECENT_PURCHASE_DAYS), 10.0, 100.0 + RECENT_PURCHASE_DAYS * 0.05)
    prices.priceHistoryByFundId = mapOf(fund.fundId to decliningPriceHistory(fund, today))
    prices.quoteOnly(fund, today, 100.0)
}

/** Köpet i [holdRecentWithLongHistory] — tre månader, på [decliningPriceHistory]s femdagarsrutnät. */
const val RECENT_PURCHASE_DAYS = 90L

/**
 * Analysen av [holdRecentWithLongHistory] (issue #214): S1 och S2 räknas på fondens långa historik
 * (dagens 100 under både 52-veckorstoppen och 200-dagarssnittet → röd), medan "sedan köp" och
 * periodradernas kronor utgår från köpet och "1 år"-procenten är fondens kurs.
 */
fun assertRecentWithLongHistoryAnalysis(analysis: FundAnalysisCalc.Analysis) {
    assertNotNull(analysis.distanceFromHigh)
    assertEquals(FundAnalysisCalc.SignalLevel.GUL, analysis.trend!!.level)
    assertEquals(FundAnalysisCalc.SignalLevel.ROD, analysis.status)
    val sedanKop = analysis.keyFigures.periodReturns.first { it.period == FundAnalysisCalc.Period.SEDAN_KOP }
    val ettAr = analysis.keyFigures.periodReturns.first { it.period == FundAnalysisCalc.Period.ETT_AR }
    assertEquals(100.0 / 104.5 - 1.0, sedanKop.fraction!!, 1e-9)
    assertEquals(-45.0, sedanKop.amount!!, 1e-9) // 10 × (100 − 104,5)
    assertEquals(-45.0, ettAr.amount!!, 1e-9) // bara andelarna som ägts, från köpet
    assertEquals(100.0 / 118.25 - 1.0, ettAr.fraction!!, 1e-9) // fondens kurs 365 dagar sedan
    assertNull(analysis.keyFigures.cagr)
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
