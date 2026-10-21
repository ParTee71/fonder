package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import kotlin.math.max
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/**
 * Ungefärlig skatt på fondsparandet (issue #199, skill `fondmatematik` → Anskaffningsvärde och skatt):
 *
 * - **ISK/KF:** schablonskatt = (snittunderlag − skattefri nivå) × max(1,25 %, statslåneräntan + 1 pp) × 30 %,
 *   där snittunderlaget är värdet vid varje kvartals början plus årets insättningar, delat med fyra
 *   ([iskTax], [iskTaxForYear]).
 * - **Depå/AF:** 30 % på årets realiserade resultat efter kvittning ([realizedTax]), skatt på
 *   schablonintäkten 0,4 % av värdet vid årets början (kursen föregående bankdag) ([fundStandardIncomeTaxForYear]) och skatten
 *   ett byte skulle kosta ([switchTax]).
 *
 * Ren beräkning utan Android; de förväntade värdena i testet kommer ur `tools/fondmatte`
 * (`fondmatte.py isk`, `fondmatte.py depa`). Siffrorna är ungefärliga — deklarationen avgör, och
 * den skattefria nivån gäller per person över alla ISK, KF och PEPP, som appen inte ser.
 */
object TaxCalc {

    /** Skatt på inkomst av kapital. */
    const val CAPITAL_INCOME_TAX = 0.30

    /** Schablonräntans golv på ISK/KF. */
    const val ISK_RATE_FLOOR = 0.0125

    /** Schablonräntan är statslåneräntan den 30 november året före plus en procentenhet. */
    const val ISK_RATE_MARKUP = 0.01

    /** Schablonintäkt på fondandelar i depå/AF: andel av värdet vid årets början. */
    const val FUND_STANDARD_INCOME_RATE = 0.004

    /** Beloppet "Skatt per 10 000 kr som byts" räknas på (Fonddetalj, depå/AF). */
    const val SWITCH_EXAMPLE_AMOUNT = 10_000.0

    /** Ett års parametrar för ISK/KF (Skatteverket, "Belopp och procent"). */
    data class IskParameters(
        /** Statslåneräntan den 30 november året före, som andel (0,0255 = 2,55 %). */
        val governmentBondRate: Double,
        /** Skattefri nivå per person, sammanlagt över ISK, KF och PEPP. */
        val taxFreeAmount: Double
    ) {
        /** Schablonräntan: statslåneräntan + 1 pp, lägst [ISK_RATE_FLOOR]. */
        val standardRate: Double get() = max(ISK_RATE_FLOOR, governmentBondRate + ISK_RATE_MARKUP)
    }

    /**
     * Parametrarna per år — **det enda stället i appen**. Ett nytt år läggs till här, i tabellen i
     * skill `fondmatematik` och i `ISK_PARAMETERS` i `tools/fondmatte/fondmatte.py` i samma ändring;
     * `TaxCalcTest` fäller bygget om de tre skiljer sig. Ett år som saknas ger ingen skatterad.
     */
    val ISK_PARAMETERS: Map<Int, IskParameters> = mapOf(
        2025 to IskParameters(governmentBondRate = 0.0196, taxFreeAmount = 150_000.0),
        2026 to IskParameters(governmentBondRate = 0.0255, taxFreeAmount = 300_000.0)
    )

    /** Schablonskatten på ISK/KF för [year]. */
    data class IskTax(
        val year: Int,
        val parameters: IskParameters,
        /** Snittunderlaget **före** den skattefria nivån: (kvartalsvärdena + insättningar) / 4. */
        val averageBase: Double,
        /** Skatten i kr, aldrig negativ. */
        val tax: Double
    )

    /** Årets realiserade resultat i depå/AF och den ungefärliga skatten på det. */
    data class RealizedTax(
        /** Summan av sälj med vinst. */
        val gains: Double,
        /** Summan av sälj med förlust (≤ 0). */
        val losses: Double,
        /** 30 % på nettovinsten, 0 vid nettoförlust. */
        val tax: Double
    ) {
        val net: Double get() = gains + losses
    }

    /** Skatten på schablonintäkten för fondandelar i depå/AF. */
    data class StandardIncomeTax(
        /** Fondernas värde vid årets början, se [valueAtStartOf]. */
        val valueAtYearStart: Double,
        val tax: Double
    )

    /** Vad ett byte i depå/AF kostar i skatt. */
    data class SwitchTax(
        /** Orealiserad vinst, 0 vid förlust. */
        val gain: Double,
        /** Skatten om hela innehavet byts. */
        val taxAll: Double,
        /** Skatten per [SWITCH_EXAMPLE_AMOUNT] kr som byts: vinstandelen av värdet × beloppet × 30 %. */
        val taxPerExampleAmount: Double
    )

    /**
     * Schablonskatt på ISK/KF ur de fyra kvartalens ingående värden och årets insättningar
     * (golden: `fondmatte.py isk`). Null när [year] saknar parametrar.
     */
    fun iskTax(year: Int, quarterStartValues: List<Double>, deposits: Double): IskTax? {
        require(quarterStartValues.size == QUARTERS) { "fyra kvartalsvärden krävs" }
        val parameters = ISK_PARAMETERS[year] ?: return null
        val averageBase = (quarterStartValues.sum() + deposits) / QUARTERS
        val tax = max(0.0, averageBase - parameters.taxFreeAmount) * parameters.standardRate * CAPITAL_INCOME_TAX
        return IskTax(year, parameters, averageBase, tax)
    }

    /**
     * Schablonskatt på ISK/KF för [year] ur transaktionerna och kurshistoriken: varje kvartals
     * ingående värde enligt [valueAtStartOf] — ett kvartal som ännu inte börjat räknas med dagens
     * värde — plus årets insättningar ([yearDeposits]). Null när året saknar parametrar eller ett
     * innehavs värde inte går att avgöra.
     */
    fun iskTaxForYear(year: Int, today: LocalDate, transactions: List<Transaction>, historyByFundId: Map<String, List<FundPrice>>): IskTax? {
        if (year !in ISK_PARAMETERS) return null
        val quarterValues = (0 until QUARTERS).map { quarter ->
            valueAtStartOf(LocalDate.of(year, 1 + quarter * MONTHS_PER_QUARTER, 1), today, transactions, historyByFundId) ?: return null
        }
        return iskTax(year, quarterValues, yearDeposits(transactions, year))
    }

    /**
     * Årets insättningar, ungefärligt: köp (med avgift) minus sälj (efter avgift) under [year],
     * lägst 0. Appen ser bara fondtransaktioner, inte kontots kontanter, så ett byte (sälj + köp)
     * räknas inte som en insättning; ett uttag som följs av en ny insättning underskattas.
     */
    fun yearDeposits(transactions: List<Transaction>, year: Int): Double = max(
        0.0,
        transactions
            .filter { LocalDate.ofEpochDay(it.epochDay).year == year }
            .sumOf { if (it.type == TransactionType.KOP) it.amount + it.fee else -(it.amount - it.fee) }
    )

    /** Ungefärlig skatt på årets realiserade resultat i depå/AF (golden: `fondmatte.py depa --gains`). */
    fun realizedTax(sales: List<RealizedSale>, year: Int): RealizedTax {
        val gains = sales.filter { LocalDate.ofEpochDay(it.epochDay).year == year }.map { it.realizedGain }
        val positive = gains.filter { it > 0.0 }.sum()
        val negative = gains.filter { it < 0.0 }.sum()
        return RealizedTax(gains = positive, losses = negative, tax = max(0.0, positive + negative) * CAPITAL_INCOME_TAX)
    }

    /** Skatten på schablonintäkten: 0,4 % av [valueAtYearStart] × 30 % (golden: `fondmatte.py depa --jan1`). */
    fun fundStandardIncomeTax(valueAtYearStart: Double): Double = max(0.0, valueAtYearStart) * FUND_STANDARD_INCOME_RATE * CAPITAL_INCOME_TAX

    /**
     * Schablonintäktens skatt för [year] i depå/AF ur innehavets värde den 1 januari ([valueAtStartOf]).
     * Null när värdet inte går att avgöra eller inget innehav fanns vid årets början.
     */
    fun fundStandardIncomeTaxForYear(year: Int, today: LocalDate, transactions: List<Transaction>, historyByFundId: Map<String, List<FundPrice>>): StandardIncomeTax? {
        val value = valueAtStartOf(LocalDate.of(year, 1, 1), today, transactions, historyByFundId) ?: return null
        if (value <= 0.0) return null
        return StandardIncomeTax(valueAtYearStart = value, tax = fundStandardIncomeTax(value))
    }

    /** Skatten på ett byte i depå/AF av ett innehav värt [value] med anskaffningsvärdet [costBasis] (golden: `fondmatte.py depa --value --cost`). */
    fun switchTax(value: Double, costBasis: Double): SwitchTax {
        val gain = max(0.0, value - costBasis)
        val share = if (value > 0.0) gain / value else 0.0
        return SwitchTax(gain = gain, taxAll = gain * CAPITAL_INCOME_TAX, taxPerExampleAmount = SWITCH_EXAMPLE_AMOUNT * share * CAPITAL_INCOME_TAX)
    }

    /**
     * Hur långt före en periods första dag kurshistoriken måste nå för [valueAtStartOf]: dagen före
     * och [NavCalendar.MAX_NAV_GAP_DAYS] dagar till (helg och helgdag).
     */
    const val PERIOD_START_LOOKBACK_DAYS: Long = 1 + NavCalendar.MAX_NAV_GAP_DAYS

    /**
     * Innehavets värde vid [day]s början, som bankernas kontrolluppgift: andelarna efter alla
     * transaktioner **före** [day], var fond värderad till sin senaste kurs **på eller före dagen
     * före** [day] — föregående bankdag — högst [NavCalendar.MAX_NAV_GAP_DAYS] dagar gammal.
     *
     * - [day] efter [today] (ett kvartal som inte börjat): **dagens värde** — andelarna i dag × varje
     *   fonds senaste kurs.
     * - [day] redan passerad men kursen saknas eller är för gammal: null (okänt, aldrig gissat).
     *
     * Ett sälj tar aldrig innehavet under noll (samma regel som [RealizedGainCalculator]).
     */
    fun valueAtStartOf(day: LocalDate, today: LocalDate, transactions: List<Transaction>, historyByFundId: Map<String, List<FundPrice>>): Double? {
        val notStarted = day.isAfter(today)
        val unitsBefore = if (notStarted) today.toEpochDay() + 1 else day.toEpochDay()
        val anchor = day.toEpochDay() - 1
        val sharesByFund = sharesBefore(transactions, unitsBefore)
        return sharesByFund.entries.sumOf { (fundId, shares) ->
            val history = historyByFundId[fundId].orEmpty()
            val price = if (notStarted) NavCalendar.latestOnOrBefore(history, today.toEpochDay()) else NavCalendar.startPriceOnOrBefore(history, anchor)
            val nav = price?.nav ?: return null
            shares * nav
        }
    }

    /** Andelar per fond efter transaktionerna före [epochDay], i datumordning; ett sälj tar aldrig innehavet under noll. */
    private fun sharesBefore(transactions: List<Transaction>, epochDay: Long): Map<String, Double> {
        val shares = mutableMapOf<String, Double>()
        transactions
            .filter { it.epochDay < epochDay }
            .sortedWith(compareBy({ it.epochDay }, { it.id }))
            .forEach { tx ->
                val held = (shares[tx.fundId] ?: 0.0) + if (tx.type == TransactionType.KOP) tx.shares else -tx.shares
                if (held > SHARE_EPSILON) shares[tx.fundId] = held else shares.remove(tx.fundId)
            }
        return shares
    }

    private const val QUARTERS = 4
    private const val MONTHS_PER_QUARTER = 3
    private const val SHARE_EPSILON = 1e-9
}
