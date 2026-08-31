package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundPrice
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.Transaction

/**
 * Portföljens **tidsviktade avkastning över tid** (HEM-9) — hur de fonder man ägt har utvecklats,
 * oberoende av när pengarna sattes in.
 *
 * Måttet är en kedja av dagsavkastningar: för varje par av på varandra följande dagar i tidslinjen
 * värderas **gårdagens andelar** till båda dagarnas NAV, och kvoten dem emellan blir dagens faktor.
 * Faktorerna multipliceras ihop till ett index som startar på 1,0 (kurvan ritar `index − 1`, dvs.
 * fortfarande en andel: 0,12 = +12 %). Eftersom varje dag mäts på andelar som ägdes **innan** dagens
 * transaktioner faller kassaflödena ur formeln av sig själva: pengar som köps in i dag börjar räknas
 * först i morgon, och en fond som säljs i dag får ändå med sin rörelse i dag.
 *
 * Det här ersätter (issue #116) det tidigare måttet `(värde − nettoinvesterat) / nettoinvesterat`
 * räknat om per dag. Det var en ögonblicksbild av *avkastningen på insatt kapital*, och två saker
 * sänkte det utan att någon fond gått ned: en insättning späder ut den upparbetade procenten, och ett
 * sälj eller ett byte stryker den realiserade vinsten ur serien helt (positionen försvinner ur
 * [PortfolioCalc.computeHoldings] tillsammans med sitt anskaffningsvärde). När periodväljaren sedan
 * nollställde mot fönstrets första dag ([ChartSeriesNormalizer.rebaseReturns]) blev periodsiffran en
 * funktion av *när* pengarna kom in: ett femårsfönster vars basdag låg strax före en stor påfyllning
 * kunde visa någon enstaka procent samtidigt som ett-, tre- och treårsfönstren såg utmärkta ut. En
 * kedjad serie har inte det problemet — ett fönster ur kedjan **är** produkten av periodens
 * dagsfaktorer, så alla perioder är jämförbara med varandra och med ett fondfaktablad.
 *
 * Priset för det är att slutpunkten inte längre är [PortfolioCalc.totalGainLossFraction]. De två
 * svarar medvetet på olika frågor — totalkortet på "hur mycket har jag tjänat på insatt kapital",
 * kurvan på "hur gick fonderna" — och skillnaden sägs rakt ut i texten under diagrammet i stället
 * för att döljas.
 *
 * Tidslinjen är **kända NAV-dagar plus transaktionsdagar**, inte kalenderdagar: en helg är inte en
 * dag portföljen rörde sig, men en dag man handlat är en dag portföljen ändrades (och kan värderas
 * till senast kända NAV). Extra dagar kan aldrig förvanska en kedja — en dag utan ny kurs ger faktor
 * 1,0 — men utan transaktionsdagen hade rörelsen mellan köpet och nästa NAV-dag fallit bort. Punkter
 * före första köpet finns inte; då fanns ingen portfölj att mäta.
 *
 * Ett innehav utan känd NAV en viss dag utesluts ur dagens faktor — samma princip som
 * [PortfolioCalc.totalGainLossFraction], som annars hade låtit en fond utan kurs se ut som en total
 * förlust — och serien markeras då [Result.partial] i stället för att tyst visa en portfölj som inte
 * är hela portföljen (HEM-2). Saknas kursen bara för en enstaka dag framåtfylls den i stället (se
 * [navOnOrBefore]) och dagen ger faktor 1,0.
 *
 * **Kedjan minns fel.** Ett felaktigt NAV i källan (en spik, en ojusterad andelssplit) blir en
 * permanent nivåförskjutning för allt efter den dagen, medan det gamla kvotmåttet självläkte nästa
 * dag. Källorna är oofficiella (TP-9/TP-14) och det är en medveten avvägning: en kedja är det enda
 * sättet att göra perioderna jämförbara.
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

    /** Fond-id-prefix för den syntetiska skuggportföljen i [benchmark] — aldrig en riktig fonds identitet. */
    private const val BENCHMARK_FUND_ID = "__benchmark__"

    /**
     * En del av referensen: hur stor andel av varje insättning som läggs här, och fondens
     * kurshistorik. Vikterna förutsätts summera till 1,0 (se [IndexBenchmarkSelector.Benchmark]).
     */
    data class BenchmarkComponent(val weight: Double, val history: List<FundPrice>)

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
        val transactionDays = transactions.map { it.epochDay }.distinct().sorted()
        // Transaktionsdagarna ingår i tidslinjen även om de inte är NAV-dagar: annars föll rörelsen
        // mellan ett köp gjort på en helg och nästa kursdag bort ur kedjan.
        val timeline = (sortedHistory.values.flatMap { prices -> prices.map { it.epochDay } } + transactionDays)
            .filter { it >= firstTransactionDay }
            .distinct()
            .sorted()
        if (timeline.isEmpty()) return Result.EMPTY

        // Innehav ändras bara på transaktionsdagar, så FIFO körs en gång per segment i stället för en
        // gång per dag — annars hade "Allt" för en flerårig portfölj räknat om hela
        // transaktionshistoriken tusentals gånger vid varje omkomposition.
        var nextTransactionIndex = 0
        var holdings = emptyList<Holding>()
        var holdingsComputed = false

        // Kurscursorn är monoton, precis som tidslinjen: varje fonds historik gås igenom en gång
        // totalt i stället för att sökas om från början för varje dag.
        val priceCursor = mutableMapOf<String, Int>()

        val points = mutableListOf<Pair<Long, Double>>()
        var partial = false

        // Kedjans tillstånd: gårdagens innehav och gårdagens kurser. Dagens avkastning mäts på dem,
        // inte på dagens — det är precis det som gör måttet oberoende av dagens in- och utflöden.
        var index = 1.0
        var started = false
        var previousHoldings = emptyList<Holding>()
        var previousNav = emptyMap<String, Double?>()

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

            val navToday = (previousHoldings + holdings)
                .map { it.fund.fundId }
                .distinct()
                .associateWith { fundId -> navOnOrBefore(fundId, day, sortedHistory, priceCursor) }

            if (started) {
                var valueYesterday = 0.0
                var valueToday = 0.0
                for (holding in previousHoldings) {
                    val before = previousNav[holding.fund.fundId]
                    val now = navToday[holding.fund.fundId]
                    if (before == null || now == null) {
                        partial = true
                        continue
                    }
                    valueYesterday += holding.netShares * before
                    valueToday += holding.netShares * now
                }
                // Ingen värderbar portfölj i går (t.ex. dagarna mellan ett sälj och nästa köp under
                // ett fondbyte) → faktor 1,0. Kedjan pausar på sin nivå i stället för att brytas.
                if (valueYesterday > 0.0) index *= valueToday / valueYesterday
                points += day to (index - 1.0)
            } else {
                var value = 0.0
                for (holding in holdings) {
                    val now = navToday[holding.fund.fundId]
                    if (now == null) {
                        partial = true
                        continue
                    }
                    value += holding.netShares * now
                }
                // Kedjan startar först den dag portföljen går att värdera — dessförinnan finns inget
                // att mäta, och en 0 %-punkt där hade påstått att en omätbar dag var en oförändrad dag.
                if (value > 0.0) {
                    started = true
                    points += day to 0.0
                }
            }

            previousHoldings = holdings
            previousNav = navToday
        }

        return Result(points = points, partial = partial)
    }

    /**
     * Skuggportföljen (HEM-10): **samma** insättningar och uttag, samma dagar och samma
     * kronbelopp, lagda i referensen i stället. Varje insättning delas efter [components]
     * vikter, och varje del köper andelar till respektive fonds NAV den dagen (`belopp / NAV`).
     * Serien räknas sedan med exakt samma maskineri som [compute] — två kurvor med samma mått,
     * samma fonder över tid och samma historikkrav, där skillnaden dem emellan är
     * alternativkostnaden för de egna fondvalen.
     *
     * Sedan issue #116 är båda kurvorna tidsviktade, så jämförelsen är kassaflödesfri på båda sidor.
     * Skuggportföljen behövs ändå: det är den som håller referensens vikter i takt med de egna
     * köpen och som spärrar en jämförelse vars historik inte räcker (se nedan).
     *
     * Blandningen speglar portföljens aktieandel (issue #101), så skillnaden inte bara mäter
     * att portföljen råkar ha en annan tillgångsfördelning än referensen — se
     * [IndexBenchmarkSelector].
     *
     * Null om någon komponents historik inte når tillbaka till **varje** transaktionsdag: en
     * skuggportfölj som saknar sitt första köp har inte samma vikter, och en jämförelse som tyst
     * hoppar över en insättning är sämre än ingen jämförelse alls (samma
     * hellre-markerat-än-gissat-princip som HEM-2).
     *
     * Avgifter (courtage) tas inte med i skuggportföljen: de påverkar inte anskaffningsvärdet
     * på köpsidan ([RealizedGainCalculator]), och att låtsas att ett fondbyte i en indexfond
     * hade kostat exakt lika mycket vore en gissning.
     */
    fun benchmark(transactions: List<Transaction>, components: List<BenchmarkComponent>): Result? {
        if (transactions.isEmpty() || components.isEmpty()) return null
        if (components.any { it.history.isEmpty() }) return null

        val sortedTransactions = transactions.sortedBy { it.epochDay }
        val funds = mutableListOf<Fund>()
        val historyByFundId = mutableMapOf<String, List<FundPrice>>()
        val synthetic = mutableListOf<Transaction>()

        // Varje komponent blir en egen syntetisk fond, så den viktade skuggportföljen räknas av
        // exakt samma maskineri som den riktiga: FIFO, framåtfyllda kurser och allt. En
        // enkomponentsblandning ger därför per konstruktion samma kurva som före issue #101.
        components.forEachIndexed { index, component ->
            val fundId = "$BENCHMARK_FUND_ID$index"
            val sorted = component.history.sortedBy { it.epochDay }
            funds += Fund(fundId = fundId, name = fundId)
            historyByFundId[fundId] = sorted.map { it.copy(fundId = fundId) }

            for (transaction in sortedTransactions) {
                val nav = sorted.lastOrNull { it.epochDay <= transaction.epochDay }?.nav ?: return null
                if (nav <= 0.0) return null
                val amount = transaction.amount * component.weight
                synthetic += transaction.copy(
                    fundId = fundId,
                    shares = amount / nav,
                    pricePerShare = nav,
                    fee = 0.0,
                )
            }
        }

        return compute(funds, synthetic, historyByFundId)
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
