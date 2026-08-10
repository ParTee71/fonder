package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction

/**
 * Portföljens **totala avkastning i procent över tid** (HEM-9) — historiken bakom den siffra
 * totalkortet visar som ett enda tal. Måttet är exakt [PortfolioCalc.totalGainLossFraction],
 * fast räknat om för varje känd NAV-dag: `(värde − nettoinvesterat) / nettoinvesterat`, med
 * FIFO-anskaffningsvärde ur [RealizedGainCalculator] precis som resten av appen (TP-15). Serien
 * och totalkortet får aldrig kunna svara olika på samma fråga.
 *
 * Tidslinjen är **unionen av kända NAV-dagar**, inte kalenderdagar: en helg eller en röd dag är
 * inte en dag portföljen rörde sig, och en framräknad punkt där skulle vara en påhittad
 * mätpunkt. Punkter före första köpet finns inte — då fanns ingen portfölj att mäta.
 *
 * Måttet är, precis som [PortfolioPerformanceCalc], **kassaflödesokänsligt**: en insättning
 * flyttar kvoten (nytt kapital till dagens kurs späder ut en upparbetad procent) utan att någon
 * avkastning skett. Det är samma begränsning som HEM-1 redan lever med, och skälet till att
 * köpdagarna markeras i diagrammet — och till att [benchmark] finns: en skuggportfölj med
 * *samma* kassaflöden är det enda sättet att jämföra bort just den effekten.
 *
 * Ett innehav utan känd NAV en viss dag utesluts ur **både** värde och nettoinvesterat den
 * dagen — samma princip som [PortfolioCalc.totalGainLossFraction], som annars hade låtit en
 * fond utan kurs se ut som en total förlust — och serien markeras då [Result.partial] i stället
 * för att tyst visa en portfölj som inte är hela portföljen (HEM-2).
 */
object PortfolioReturnSeriesCalc {

    /**
     * @param points (epochDay, avkastning som andel), stigande datumordning. Tom = historiken
     *   räcker inte till en enda mätpunkt; vyn visar då en förklaring, aldrig ett tomt diagram.
     * @param partial sant om minst en dag räknats utan ett innehav som saknade NAV den dagen.
     */
    data class Result(val points: List<Pair<Long, Double>>, val partial: Boolean) {
        val isEmpty: Boolean get() = points.isEmpty()

        companion object {
            val EMPTY = Result(points = emptyList(), partial = false)
        }
    }

    /** Fond-id för den syntetiska skuggportföljen i [benchmark] — aldrig en riktig fonds identitet. */
    private const val BENCHMARK_FUND_ID = "__benchmark__"

    /**
     * Avkastningsserien för hela portföljen.
     *
     * [historyByFundId] bör nå tillbaka till första köpet; punkter kan bara skapas för dagar
     * som finns där. Historik *före* första köpet skadar inte — den används bara för att kunna
     * framåtfylla en kurs på själva köpdagen om den inte var en NAV-dag.
     */
    fun compute(
        funds: List<Fund>,
        transactions: List<Transaction>,
        historyByFundId: Map<String, List<FundPrice>>,
    ): Result {
        if (transactions.isEmpty()) return Result.EMPTY
        val firstTransactionDay = transactions.minOf { it.epochDay }

        val sortedHistory = historyByFundId.mapValues { (_, prices) -> prices.sortedBy { it.epochDay } }
        val timeline = sortedHistory.values
            .flatMap { prices -> prices.map { it.epochDay } }
            .filter { it >= firstTransactionDay }
            .distinct()
            .sorted()
        if (timeline.isEmpty()) return Result.EMPTY

        // Innehav och anskaffningsvärde ändras bara på transaktionsdagar, så FIFO körs en gång
        // per segment i stället för en gång per dag — annars hade "Allt" för en flerårig portfölj
        // räknat om hela transaktionshistoriken tusentals gånger vid varje omkomposition.
        val transactionDays = transactions.map { it.epochDay }.distinct().sorted()
        var nextTransactionIndex = 0
        var holdings = emptyList<Holding>()
        var holdingsComputed = false

        // Kurscursorn är monoton, precis som tidslinjen: varje fonds historik gås igenom en gång
        // totalt i stället för att sökas om från början för varje dag.
        val priceCursor = mutableMapOf<String, Int>()

        val points = mutableListOf<Pair<Long, Double>>()
        var partial = false

        for (day in timeline) {
            var segmentChanged = !holdingsComputed
            while (nextTransactionIndex < transactionDays.size && transactionDays[nextTransactionIndex] <= day) {
                nextTransactionIndex++
                segmentChanged = true
            }
            if (segmentChanged) {
                holdings = PortfolioCalc.computeHoldings(funds, transactions.filter { it.epochDay <= day })
                holdingsComputed = true
            }
            if (holdings.isEmpty()) continue

            var value = 0.0
            var invested = 0.0
            for (holding in holdings) {
                val nav = navOnOrBefore(holding.fund.fundId, day, sortedHistory, priceCursor)
                if (nav == null) {
                    partial = true
                    continue
                }
                value += holding.netShares * nav
                invested += holding.netInvested
            }
            if (invested <= 0.0) continue
            points += day to (value - invested) / invested
        }

        return Result(points = points, partial = partial)
    }

    /**
     * Skuggportföljen (HEM-10): **samma** insättningar och uttag, samma dagar och samma
     * kronbelopp, lagda i referensfonden i stället. Andelarna räknas om till referensfondens
     * NAV den dagen (`belopp / NAV`) och serien räknas sedan med exakt samma maskineri som
     * [compute] — det är hela poängen: två kurvor med samma mått och samma kassaflöden, där
     * skillnaden dem emellan är alternativkostnaden för de egna fondvalen, inte en artefakt av
     * när pengarna sattes in.
     *
     * Null om referensfondens historik inte når tillbaka till **varje** transaktionsdag: en
     * skuggportfölj som saknar sitt första köp är inte samma kassaflöden, och en jämförelse som
     * tyst hoppar över en insättning är sämre än ingen jämförelse alls (samma
     * hellre-markerat-än-gissat-princip som HEM-2).
     *
     * Avgifter (courtage) tas inte med i skuggportföljen: de påverkar inte anskaffningsvärdet
     * på köpsidan ([RealizedGainCalculator]), och att låtsas att ett fondbyte i en indexfond
     * hade kostat exakt lika mycket vore en gissning.
     */
    fun benchmark(transactions: List<Transaction>, benchmarkHistory: List<FundPrice>): Result? {
        if (transactions.isEmpty() || benchmarkHistory.isEmpty()) return null

        val sorted = benchmarkHistory.sortedBy { it.epochDay }
        val synthetic = transactions.sortedBy { it.epochDay }.map { transaction ->
            val nav = sorted.lastOrNull { it.epochDay <= transaction.epochDay }?.nav ?: return null
            if (nav <= 0.0) return null
            transaction.copy(
                fundId = BENCHMARK_FUND_ID,
                shares = transaction.amount / nav,
                pricePerShare = nav,
                fee = 0.0,
            )
        }

        val fund = Fund(fundId = BENCHMARK_FUND_ID, name = BENCHMARK_FUND_ID)
        val history = sorted.map { it.copy(fundId = BENCHMARK_FUND_ID) }
        return compute(listOf(fund), synthetic, mapOf(BENCHMARK_FUND_ID to history))
    }

    /**
     * Senast kända NAV på eller före [day] — framåtfyllt, så en helg eller en fond vars källa
     * släpar inte skapar ett hål i kurvan. Null tills fondens historik börjat, dvs. innan det
     * finns någon kurs alls att fylla framåt från.
     */
    private fun navOnOrBefore(
        fundId: String,
        day: Long,
        sortedHistory: Map<String, List<FundPrice>>,
        cursor: MutableMap<String, Int>,
    ): Double? {
        val prices = sortedHistory[fundId] ?: return null
        var index = cursor[fundId] ?: -1
        while (index + 1 < prices.size && prices[index + 1].epochDay <= day) index++
        cursor[fundId] = index
        return if (index >= 0) prices[index].nav else null
    }
}
