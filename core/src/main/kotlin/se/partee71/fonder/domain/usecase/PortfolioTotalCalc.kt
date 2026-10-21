package se.partee71.fonder.domain.usecase

import kotlin.math.pow
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

/**
 * Portföljens **totalresultat** och **pengaviktade avkastning** (HEM-1/POR-3, issue #191) — svaret
 * på "hur mycket har jag tjänat?" och "hur gick det för mina pengar?", till skillnad från
 * [PortfolioCalc.totalGainLossFraction] som är orealiserat resultat på de andelar som finns kvar
 * och [PortfolioReturnSeriesCalc] som är fondernas tidsviktade utveckling (skill `fondmatematik`,
 * tabellen Avkastning).
 *
 * - **Totalresultat** = dagens värde + uttag − insättningar, avgifter inräknade (köpavgiften är en
 *   insättning, säljavgiften minskar uttaget). Realiserat, orealiserat och avgifter i ett tal;
 *   procenten är mot insatt netto. Golden: `total_gain`, `total_gain_fraction`, `net_deposits` i
 *   `tools/fondmatte`.
 * - **Pengaviktad avkastning (XIRR)** = räntan som nollställer kassaflödena (köp negativa, sälj
 *   positiva, slutvärdet positivt), dagräkning ACT/365F som Excel och pyxirr. Visas i årstakt bara
 *   när portföljen är minst [ANNUALIZE_MIN_DAYS] gammal (GIPS) — dessförinnan null, aldrig en
 *   årstakt extrapolerad ur några månader. Golden: `xirr`.
 *
 * Saknar ett innehav känd kurs är värdet okänt, och då är både resultatet och XIRR null i stället
 * för ett tal räknat på en del av portföljen ([Result.partial], samma princip som HEM-2).
 */
object PortfolioTotalCalc {

    /** Dagräkning för XIRR (ACT/365F), inte [FundAnalysisCalc]s 365,2425 — se skill `fondmatematik`. */
    const val XIRR_DAYS_PER_YEAR = 365.0

    /** Ingen årstakt under ett år (GIPS). */
    const val ANNUALIZE_MIN_DAYS = 365L

    /**
     * @param netDepositsKr insatt netto: köp inklusive avgift minus sälj efter avgift.
     * @param totalGainKr totalresultat i kr, null när ett innehav saknar kurs ([partial]).
     * @param totalGainFraction [totalGainKr] / [netDepositsKr], null när nettot inte är positivt
     *   (allt uttaget och mer därtill — då finns inget insatt att mäta mot) eller [partial].
     * @param moneyWeightedAnnualReturn XIRR i årstakt, null när portföljen är yngre än ett år,
     *   ekvationen saknar lösning eller [partial].
     * @param partial sant när minst ett innehav saknar känd kurs.
     * @param ageDays portföljens ålder i dagar, första transaktionsdagen till värderingsdagen (eller
     *   sista transaktionsdagen om den är senare) — så att kortet kan säga "visas efter ett år" när
     *   [moneyWeightedAnnualReturn] är null av den orsaken och inte av någon annan.
     */
    data class Result(
        val netDepositsKr: Double,
        val totalGainKr: Double?,
        val totalGainFraction: Double?,
        val moneyWeightedAnnualReturn: Double?,
        val partial: Boolean,
        val ageDays: Long
    ) {
        /** Sant när årstakten saknas bara för att portföljen är yngre än [ANNUALIZE_MIN_DAYS]. */
        val youngerThanYear: Boolean get() = ageDays < ANNUALIZE_MIN_DAYS

        companion object {
            val EMPTY = Result(netDepositsKr = 0.0, totalGainKr = null, totalGainFraction = null, moneyWeightedAnnualReturn = null, partial = false, ageDays = 0)
        }
    }

    /**
     * @param transactions alla transaktioner, även för helt avsålda fonder — deras sälj är uttag.
     * @param holdings kvarvarande innehav med [Holding.currentValue] satt där kurs finns
     *   ([PortfolioCalc.withCurrentValue]).
     * @param valuationEpochDay dagen slutvärdet avser (XIRR:s sista kassaflöde), normalt den
     *   senaste kända NAV-dagen bland innehaven; null när inget innehav finns (allt sålt), då
     *   avslutas kassaflödena på sista transaktionsdagen med värdet 0.
     */
    fun compute(transactions: List<Transaction>, holdings: List<Holding>, valuationEpochDay: Long?): Result {
        if (transactions.isEmpty()) return Result.EMPTY
        val netDeposits = transactions.sumOf { it.signedCashFlow() * -1.0 }
        val firstDay = transactions.minOf { it.epochDay }
        val lastDay = maxOf(valuationEpochDay ?: Long.MIN_VALUE, transactions.maxOf { it.epochDay })
        val ageDays = lastDay - firstDay
        val partial = holdings.any { it.currentValue == null }
        if (partial) return Result(netDepositsKr = netDeposits, totalGainKr = null, totalGainFraction = null, moneyWeightedAnnualReturn = null, partial = true, ageDays = ageDays)

        val value = holdings.sumOf { it.currentValue ?: 0.0 }
        val gain = value - netDeposits
        val fraction = if (netDeposits > 0.0) gain / netDeposits else null

        val flows = transactions.map { it.epochDay to it.signedCashFlow() } + ((valuationEpochDay ?: lastDay) to value)
        return Result(
            netDepositsKr = netDeposits,
            totalGainKr = gain,
            totalGainFraction = fraction,
            moneyWeightedAnnualReturn = xirr(flows),
            partial = false,
            ageDays = ageDays
        )
    }

    /** Kassaflödet ur användarens perspektiv: köp är pengar ut (negativt, inkl. avgift), sälj pengar in (efter avgift). */
    private fun Transaction.signedCashFlow(): Double = when (type) {
        TransactionType.KOP -> -(amount + fee)
        TransactionType.SALJ -> amount - fee
    }

    /**
     * XIRR ur (epochDay, belopp)-flöden, ACT/365F. Null när perioden är kortare än
     * [ANNUALIZE_MIN_DAYS], när flödena saknar både in- och utbetalning, eller när ekvationen inte
     * har någon rot i [XIRR_MIN_RATE, XIRR_MAX_RATE].
     *
     * Löses med inringning och bisektion i stället för Newton: extrema flöden kan ge flera eller
     * inga lösningar, och en ren Newton kan då divergera eller hitta en rot utanför det rimliga
     * (skill `fondmatematik`). Bisektionen är deterministisk och konvergerar till under 1e-12 på
     * de iterationer som görs här.
     */
    fun xirr(flows: List<Pair<Long, Double>>): Double? {
        if (flows.isEmpty()) return null
        val firstDay = flows.minOf { it.first }
        val lastDay = flows.maxOf { it.first }
        if (lastDay - firstDay < ANNUALIZE_MIN_DAYS) return null
        if (flows.none { it.second < 0.0 } || flows.none { it.second > 0.0 }) return null

        fun npv(rate: Double): Double = flows.sumOf { (day, amount) -> amount / (1.0 + rate).pow((day - firstDay) / XIRR_DAYS_PER_YEAR) }

        var low = XIRR_MIN_RATE
        var high = XIRR_MAX_RATE
        var fLow = npv(low)
        var fHigh = npv(high)
        if (fLow == 0.0) return low
        if (fHigh == 0.0) return high
        if (fLow.sign() == fHigh.sign()) return null
        repeat(XIRR_ITERATIONS) {
            val mid = (low + high) / 2.0
            val fMid = npv(mid)
            if (fMid == 0.0 || (high - low) / 2.0 < XIRR_TOLERANCE) return mid
            if (fMid.sign() == fLow.sign()) {
                low = mid
                fLow = fMid
            } else {
                high = mid
                fHigh = fMid
            }
        }
        return (low + high) / 2.0
    }

    private fun Double.sign(): Int = if (this < 0.0) -1 else 1

    /** Inringningen: −99,99 % till +1 000 % per år täcker varje verklig fondportfölj; utanför finns ingen rot värd att visa. */
    private const val XIRR_MIN_RATE = -0.9999
    private const val XIRR_MAX_RATE = 10.0
    private const val XIRR_TOLERANCE = 1e-13
    private const val XIRR_ITERATIONS = 200
}
