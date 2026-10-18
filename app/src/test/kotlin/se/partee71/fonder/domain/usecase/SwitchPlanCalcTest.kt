package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.Holding
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType

class SwitchPlanCalcTest {

    private fun fund(fundId: String, isin: String? = "SE$fundId") = Fund(fundId = fundId, name = fundId, isin = isin)

    private fun holding(fundId: String, value: Double?, isin: String? = "SE$fundId") = Holding(fund = fund(fundId, isin), netShares = 1.0, netInvested = 0.0, currentValue = value)

    private fun metadata(isin: String, risk: Int?, fee: Double?) = FundMetadata(
        isin = isin, name = isin, orderbookId = isin, totalFee = fee, managementFee = fee,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList()
    )

    private fun candidate(isin: String, risk: Int, fee: Double, twelveMonthReturn: Double) = SwitchPlanCalc.Candidate(metadata(isin, risk, fee), twelveMonthReturn)

    /** Som [SwitchPlanCalc.plan], utan avstådda par om inget anges — de flesta fallen rör inte dem. */
    private fun plan(
        holdings: List<Holding>,
        metadataByIsin: Map<String, FundMetadata>,
        candidates: List<SwitchPlanCalc.Candidate>,
        targetAllocation: Map<Int, Double>,
        excludedPairs: Set<Pair<String, String>> = emptySet()
    ) = SwitchPlanCalc.plan(holdings, metadataByIsin, candidates, targetAllocation, excludedPairs)

    private fun underweightedLevels(
        holdings: List<Holding>,
        metadataByIsin: Map<String, FundMetadata>,
        targetAllocation: Map<Int, Double>
    ) = SwitchPlanCalc.underweightedLevels(holdings, metadataByIsin, targetAllocation)

    @Test
    fun `tom malfordelning ger tom plan`() {
        val plan = plan(emptyList(), emptyMap(), emptyList(), emptyMap())

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `portfolj redan i linje med malet ger tom plan`() {
        val holdings = listOf(holding("A", 10_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 4, fee = 0.5))

        val plan = plan(holdings, metadataByIsin, emptyList(), mapOf(4 to 1.0))

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `enkelt byte fran overviktad till underviktad niva`() {
        val holdings = listOf(holding("A", 10_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 5, fee = 1.0))
        val candidates = listOf(candidate("SEB", risk = 3, fee = 0.3, twelveMonthReturn = 0.1))

        val plan = plan(holdings, metadataByIsin, candidates, mapOf(3 to 1.0))

        val switch = plan.single()
        assertEquals("A", switch.sellFund.fundId)
        assertEquals(10_000.0, switch.sellValueKr, 1e-9)
        assertEquals("SEB", switch.buyIsin)
        assertEquals(5, switch.fromLevel)
        assertEquals(3, switch.toLevel)
        assertEquals(0.3 - 1.0, switch.feeDelta, 1e-9)
    }

    @Test
    fun `girig sekvens r017knar om gapet mellan varje byte, fyller inte samma hink tva ganger`() {
        // Två överviktade innehav på nivå 5 (5000 kr vardera), mål 30/70 på nivå 2/3. Det
        // första bytet ska gå till nivå 3 (störst gap, 70 pp) — andra bytets gap mot nivå 3
        // ska då redan vara nedjusterat till 20 pp (inte fortfarande 70), annars skulle en
        // tredje position behövas i onödan.
        val holdings = listOf(holding("A", 5_000.0), holding("B", 5_000.0))
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", risk = 5, fee = 2.0),
            "SEB" to metadata("SEB", risk = 5, fee = 1.0)
        )
        val candidates = listOf(
            candidate("SEC3", risk = 3, fee = 0.2, twelveMonthReturn = 0.15),
            candidate("SEC2", risk = 2, fee = 0.15, twelveMonthReturn = 0.05)
        )

        val plan = plan(holdings, metadataByIsin, candidates, mapOf(2 to 0.3, 3 to 0.7))

        assertEquals(2, plan.size)
        assertEquals("A" to 3, plan[0].sellFund.fundId to plan[0].toLevel)
        assertEquals("B" to 2, plan[1].sellFund.fundId to plan[1].toLevel)
        // Byte 1 fyller nivå 3 helt (5 000 kr), byte 2 begränsas till nivå 2:s gap (3 000 kr av
        // B:s 5 000) i stället för att sälja hela positionen — se överskjutningstesterna nedan.
        assertEquals(5_000.0, plan[0].sellValueKr, 1e-9)
        assertEquals(3_000.0, plan[1].sellValueKr, 1e-9)
    }

    @Test
    fun `bytet begransas till gapet och skjuter inte forbi malet`() {
        // Mål 50/50 på nivå 3 och 5, nivå 5 överviktad med 10 pp. Säljs hela nivå 5-positionen
        // (6 000 kr) blir nivå 3 i stället 100 % och avvikelsen 50 pp åt andra hållet — planen
        // gjorde portföljen sämre mot sitt eget mål (issue #75, punkt 1). Bytet ska begränsas
        // till gapets 1 000 kr.
        val holdings = listOf(holding("Lag", 4_000.0, isin = "SELAG"), holding("Hog", 6_000.0, isin = "SEHOG"))
        val metadataByIsin = mapOf(
            "SELAG" to metadata("SELAG", risk = 3, fee = 0.5),
            "SEHOG" to metadata("SEHOG", risk = 5, fee = 1.0)
        )
        val candidates = listOf(candidate("SEC3", risk = 3, fee = 0.2, twelveMonthReturn = 0.1))

        val plan = plan(holdings, metadataByIsin, candidates, mapOf(3 to 0.5, 5 to 0.5))

        // Ett enda byte som stänger gapet helt — ingen andra rad som säljer tillbaka.
        val switch = plan.single()
        assertEquals("Hog", switch.sellFund.fundId)
        assertEquals(1_000.0, switch.sellValueKr, 1e-9)
    }

    @Test
    fun `delvis sald position ligger kvar och kan fylla en annan underviktad niva`() {
        // Hela portföljen på nivå 5, mål 20/30/50 över nivå 2/3/5. Positionen räcker till båda
        // de underviktade nivåerna — den ska säljas i två delar (3 000 + 2 000), inte tömmas på
        // det första bytet.
        val holdings = listOf(holding("Enda", 10_000.0, isin = "SEENDA"))
        val metadataByIsin = mapOf("SEENDA" to metadata("SEENDA", risk = 5, fee = 1.0))
        val candidates = listOf(
            candidate("SEC3", risk = 3, fee = 0.2, twelveMonthReturn = 0.15),
            candidate("SEC2", risk = 2, fee = 0.15, twelveMonthReturn = 0.05)
        )

        val plan = plan(holdings, metadataByIsin, candidates, mapOf(2 to 0.2, 3 to 0.3, 5 to 0.5))

        assertEquals(2, plan.size)
        assertTrue("båda bytena säljer ur samma position", plan.all { it.sellFund.fundId == "Enda" })
        assertEquals(3_000.0, plan[0].sellValueKr, 1e-9)
        assertEquals(3, plan[0].toLevel)
        assertEquals(2_000.0, plan[1].sellValueKr, 1e-9)
        assertEquals(2, plan[1].toLevel)
    }

    @Test
    fun `tak pa antal byten per plan aven om fler skulle behovas`() {
        val holdings = (1..4).map { i -> holding("H$i", 2_500.0) }
        val metadataByIsin = (1..4).associate { i -> "SEH$i" to metadata("SEH$i", risk = 6, fee = (5 - i).toDouble()) }
        val candidates = (1..4).map { i -> candidate("SEC$i", risk = 1, fee = 0.1, twelveMonthReturn = 0.1) }

        val plan = plan(holdings, metadataByIsin, candidates, mapOf(1 to 1.0))

        assertEquals(SwitchPlanCalc.MAX_SWITCHES_PER_PLAN, plan.size)
    }

    @Test
    fun `sarjkandidat inom en niva ar den med hogst avgift`() {
        val holdings = listOf(holding("Billig", 1_000.0, isin = "SEBILLIG"), holding("Dyr", 1_000.0, isin = "SEDYR"))
        val metadataByIsin = mapOf(
            "SEBILLIG" to metadata("SEBILLIG", risk = 5, fee = 0.5),
            "SEDYR" to metadata("SEDYR", risk = 5, fee = 2.0)
        )
        val candidates = listOf(candidate("SEC", risk = 3, fee = 0.2, twelveMonthReturn = 0.1))

        val plan = plan(holdings, metadataByIsin, candidates, mapOf(3 to 1.0))

        assertEquals("Dyr", plan.first().sellFund.fundId)
    }

    @Test
    fun `kopkandidat ar oversta kvartilen pa avkastning, darefter lagst avgift`() {
        val holdings = listOf(holding("A", 10_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 5, fee = 1.0))
        // 8 kandidater -> kvartil = 2 (störst avkastning). Den tredje kandidaten har lägst
        // avgift av alla, men ska ändå INTE väljas eftersom den inte ligger i topp-kvartilen.
        val candidates = listOf(
            candidate("HOG_DYR", risk = 3, fee = 0.9, twelveMonthReturn = 0.30),
            candidate("HOG_BILLIG", risk = 3, fee = 0.2, twelveMonthReturn = 0.25),
            candidate("BILLIGAST_MEN_LAG_AVKASTNING", risk = 3, fee = 0.05, twelveMonthReturn = 0.20),
            candidate("C4", risk = 3, fee = 0.5, twelveMonthReturn = 0.15),
            candidate("C5", risk = 3, fee = 0.5, twelveMonthReturn = 0.10),
            candidate("C6", risk = 3, fee = 0.5, twelveMonthReturn = 0.05),
            candidate("C7", risk = 3, fee = 0.5, twelveMonthReturn = 0.02),
            candidate("C8", risk = 3, fee = 0.5, twelveMonthReturn = 0.01)
        )

        val plan = plan(holdings, metadataByIsin, candidates, mapOf(3 to 1.0))

        assertEquals("HOG_BILLIG", plan.single().buyIsin)
    }

    @Test
    fun `innehav utan kand riskniva utesluts ur planen och rakas inte in i totalen`() {
        val holdings = listOf(holding("OkandRisk", 5_000.0, isin = "SEOKAND"), holding("KandRisk", 5_000.0, isin = "SEKAND"))
        val metadataByIsin = mapOf(
            "SEOKAND" to metadata("SEOKAND", risk = null, fee = 1.0),
            "SEKAND" to metadata("SEKAND", risk = 5, fee = 1.0)
        )
        val candidates = listOf(candidate("SEC", risk = 3, fee = 0.2, twelveMonthReturn = 0.1))

        val plan = plan(holdings, metadataByIsin, candidates, mapOf(3 to 1.0))

        val switch = plan.single()
        assertEquals("KandRisk", switch.sellFund.fundId)
        // Bara det kända innehavets värde räknas som portföljbas — inte 10 000.
        assertEquals(5_000.0, switch.sellValueKr, 1e-9)
    }

    @Test
    fun `innehav utan kand avgift raknas med i sin nivas vikt men kan inte saljas`() {
        // Regression (issue #75, fynd A): ett innehav utan `totalFee` föll ur *hela* beräkningen,
        // alltså även ur nämnaren och ur sin egen nivås vikt. En perfekt balanserad 50/50-portfölj
        // såg då ut som 0/100 och fick ett byte som gjorde den 75/25 — planen gjorde portföljen
        // mätbart sämre mot sitt eget mål.
        val holdings = listOf(holding("UtanAvgift", 5_000.0, isin = "SEU"), holding("MedAvgift", 5_000.0, isin = "SEM"))
        val metadataByIsin = mapOf(
            "SEU" to metadata("SEU", risk = 3, fee = null),
            "SEM" to metadata("SEM", risk = 5, fee = 1.0)
        )
        val candidates = listOf(candidate("SEC", risk = 3, fee = 0.2, twelveMonthReturn = 0.1))

        val plan = plan(holdings, metadataByIsin, candidates, mapOf(3 to 0.5, 5 to 0.5))

        assertTrue("portföljen ligger redan på målet — inget byte ska föreslås", plan.isEmpty())
    }

    @Test
    fun `overvikt som bara ligger i en fond utan kand avgift ger ingen plan`() {
        // Värdet räknas med i nivåns vikt, men utan avgiftsuppgift går fonden inte att välja som
        // säljkandidat (avgiften avgör vilken position som säljs och ingår i feeDelta).
        val holdings = listOf(holding("UtanAvgift", 10_000.0, isin = "SEU"))
        val metadataByIsin = mapOf("SEU" to metadata("SEU", risk = 5, fee = null))
        val candidates = listOf(candidate("SEC", risk = 3, fee = 0.2, twelveMonthReturn = 0.1))

        val plan = plan(holdings, metadataByIsin, candidates, mapOf(3 to 1.0))

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `ingen kopkandidat for underviktad niva ger en tom plan`() {
        val holdings = listOf(holding("A", 10_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 5, fee = 1.0))

        val plan = plan(holdings, metadataByIsin, emptyList(), mapOf(3 to 1.0))

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `innehav utan aktuellt varde eller isin exkluderas`() {
        val holdings = listOf(
            holding("UtanVarde", value = null, isin = "SEA"),
            holding("UtanIsin", value = 1_000.0, isin = null)
        )
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 5, fee = 1.0))

        val plan = plan(holdings, metadataByIsin, listOf(candidate("SEC", 3, 0.2, 0.1)), mapOf(3 to 1.0))

        assertTrue(plan.isEmpty())
    }

    @Test
    fun `en avvikelse under MIN_GAP_PP ger ingen plan`() {
        // 4,9 % avvikelse — precis under tröskeln.
        val holdings = listOf(holding("A", 9_510.0, isin = "SEA"), holding("B", 490.0, isin = "SEB"))
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", risk = 4, fee = 0.5),
            "SEB" to metadata("SEB", risk = 3, fee = 0.5)
        )

        val plan = plan(holdings, metadataByIsin, listOf(candidate("SEC", 3, 0.2, 0.1)), mapOf(4 to 1.0))

        assertTrue(plan.isEmpty())
    }

    // --- underweightedLevels: låter anroparen hämta kandidater bara för nivåer planen kan köpa på ---

    @Test
    fun `underweightedLevels ger bara nivaer over MIN_GAP_PP, storst underviktning forst`() {
        val holdings = listOf(holding("A", 10_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 5, fee = 1.0))

        val levels = underweightedLevels(
            holdings,
            metadataByIsin,
            // Nivå 3 saknar 60 pp, nivå 4 saknar 39 pp, nivå 2 saknar 1 pp (under gränsen),
            // nivå 5 är kraftigt överviktad.
            mapOf(2 to 0.01, 3 to 0.60, 4 to 0.39)
        )

        assertEquals(listOf(3, 4), levels)
    }

    @Test
    fun `underweightedLevels ger tom lista for en portfolj som redan ligger pa malet`() {
        val holdings = listOf(holding("A", 10_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 4, fee = 0.5))

        assertTrue(underweightedLevels(holdings, metadataByIsin, mapOf(4 to 1.0)).isEmpty())
    }

    @Test
    fun `underweightedLevels tacker alla nivaer plan faktiskt koper pa`() {
        // Kontraktet mellan de två: hämtar anroparen kandidater bara för underweightedLevels
        // får plan aldrig sakna en nivå den skulle ha köpt på. Att titta på utgångsläget räcker
        // eftersom ett byte aldrig kan göra den sålda nivån underviktad (issue #75, punkt 4).
        val holdings = listOf(holding("Dyr", 6_000.0, isin = "SED"), holding("Billig", 4_000.0, isin = "SEB"))
        val metadataByIsin = mapOf(
            "SED" to metadata("SED", risk = 5, fee = 1.5),
            "SEB" to metadata("SEB", risk = 5, fee = 0.4)
        )
        val target = mapOf(2 to 0.4, 3 to 0.4, 5 to 0.2)
        val candidates = listOf(
            candidate("C2", risk = 2, fee = 0.2, twelveMonthReturn = 0.10),
            candidate("C3", risk = 3, fee = 0.2, twelveMonthReturn = 0.10)
        )

        val levels = underweightedLevels(holdings, metadataByIsin, target)
        val plan = plan(holdings, metadataByIsin, candidates, target)

        assertTrue(plan.isNotEmpty())
        assertTrue(
            "plan köpte på en nivå underweightedLevels inte pekade ut",
            plan.map { it.toLevel }.all { it in levels }
        )
    }

    // --- Avstådda par (HEM-14) tas ur kandidatmängden ---

    @Test
    fun `ett avstatt par ersatts av nasta kandidat sa listan blir 1 till n utan lucka`() {
        val holdings = listOf(holding("A", 10_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 5, fee = 1.0))
        val candidates = listOf(
            candidate("SEBAST", risk = 3, fee = 0.1, twelveMonthReturn = 0.3),
            candidate("SENAST", risk = 3, fee = 0.2, twelveMonthReturn = 0.3)
        )

        val plan = plan(holdings, metadataByIsin, candidates, mapOf(3 to 1.0), excludedPairs = setOf("SEA" to "SEBAST"))

        val switch = plan.single()
        assertEquals("SENAST", switch.buyIsin)
        assertEquals(10_000.0, switch.sellValueKr, 1e-9)
    }

    /** Två positioner på nivå 5 (Dyr 2,0 %, Billig 0,5 %) och en enda kandidat C på nivå 3; mål 100 % nivå 3. */
    private fun twoSellSlotsPlan(excludedPairs: Set<Pair<String, String>>): List<SwitchPlanCalc.Switch> {
        val holdings = listOf(holding("Dyr", 5_000.0, isin = "SEDYR"), holding("Billig", 5_000.0, isin = "SEBILLIG"))
        val metadataByIsin = mapOf(
            "SEDYR" to metadata("SEDYR", risk = 5, fee = 2.0),
            "SEBILLIG" to metadata("SEBILLIG", risk = 5, fee = 0.5)
        )
        val candidates = listOf(candidate("SEC", risk = 3, fee = 0.2, twelveMonthReturn = 0.1))
        return plan(holdings, metadataByIsin, candidates, mapOf(3 to 1.0), excludedPairs = excludedPairs)
    }

    @Test
    fun `ett avstaende galler paret inte kopfonden for andra saljfonder`() {
        // Säljkandidaten är Dyr (högst avgift); paret Dyr → C är avstått och C är den enda
        // kandidaten — då prövas nästa position på nivån: Billig → C blir byte 1, så ett avstående
        // numrerar om planen i stället för att tömma den.
        val withDyrDismissed = twoSellSlotsPlan(setOf("SEDYR" to "SEC"))
        assertEquals(listOf("SEBILLIG"), withDyrDismissed.map { it.sellFund.isin })
        assertEquals(5_000.0, withDyrDismissed.single().sellValueKr, 1e-9)
        assertEquals("SEDYR", twoSellSlotsPlan(setOf("SEBILLIG" to "SEC")).first().sellFund.isin)
    }

    @Test
    fun `ar alla par pa nivan avstadda ges inget byte dar`() {
        assertTrue(twoSellSlotsPlan(setOf("SEDYR" to "SEC", "SEBILLIG" to "SEC")).isEmpty())
    }

    // --- Likvid som väntar på köp (BYT-3) ---

    private val today: LocalDate = LocalDate.of(2026, 10, 12)
    private val soldAt = today.minusDays(2).toEpochDay()

    private fun sell(id: Long, fundId: String, kr: Double, day: Long = soldAt) = Transaction(id = id, fundId = fundId, type = TransactionType.SALJ, epochDay = day, shares = 1.0, pricePerShare = kr)

    private fun buy(id: Long, fundId: String, kr: Double, day: Long = soldAt + 1, fee: Double = 0.0) = Transaction(id = id, fundId = fundId, type = TransactionType.KOP, epochDay = day, shares = 1.0, pricePerShare = kr, fee = fee)

    private fun watch(
        id: Long,
        proceedsKr: Double? = 4_000.0,
        targetLevel: Int? = 3,
        sellTransactionId: Long? = 1,
        soldAtEpochDay: Long = soldAt,
        candidates: List<String> = listOf("SEKAND"),
        sourceRecordId: Long? = null,
        closed: Boolean = false
    ) = SwitchWatch(
        id = id, sellIsin = "SEA", sellFundName = "A", soldAtEpochDay = soldAtEpochDay, proceedsKr = proceedsKr, targetLevel = targetLevel,
        sellTransactionId = sellTransactionId, sourceRecordId = sourceRecordId,
        candidates = candidates.map { SwitchWatchCandidate(isin = it, name = it) },
        closedAtEpochDay = if (closed) today.toEpochDay() else null, closeReason = if (closed) SwitchWatchCloseReason.KOPT else null
    )

    private val funds = listOf(fund("A"), fund("KAND"), fund("FORESLAGEN"), fund("NIVA3"), fund("NIVA5"), fund("UTAN", isin = null))
    private val levelMetadata = mapOf("SENIVA3" to metadata("SENIVA3", risk = 3, fee = 0.5), "SENIVA5" to metadata("SENIVA5", risk = 5, fee = 0.5))

    private fun pending(
        watches: List<SwitchWatch>,
        transactions: List<Transaction>,
        buyIsinByRecordId: Map<Long, String> = emptyMap()
    ) = SwitchPlanCalc.pendingProceedsByLevel(watches, transactions, funds, buyIsinByRecordId, levelMetadata, today)

    @Test
    fun `en oppen bevakning med saljtransaktion raknas som likvid pa sin malniva`() {
        val pending = pending(listOf(watch(1)), listOf(sell(1, "A", 4_000.0)))

        assertEquals(mapOf(3 to 4_000.0), pending)
    }

    @Test
    fun `bevakningar utan saljtransaktion belopp eller malniva samt stangda och utgangna raknas inte`() {
        val expiredDay = today.minusDays(SwitchWatchCalc.WATCH_TTL_DAYS + 1).toEpochDay()
        val watches = listOf(
            watch(1, sellTransactionId = null), // "Bevaka alternativ" ur ett förslag — pengarna är inte bevisligen fria
            watch(2, sellTransactionId = 99), // säljet är borttaget (TRX-2)
            watch(3, proceedsKr = null),
            watch(4, targetLevel = null),
            watch(5, closed = true),
            watch(6, soldAtEpochDay = expiredDay)
        )

        assertTrue(pending(watches, listOf(sell(1, "A", 4_000.0))).isEmpty())
    }

    @Test
    fun `kop sedan saljdagen i kandidat foreslagen fond eller fond pa malnivan forbrukar likviden inklusive avgift`() {
        val watches = listOf(watch(1, proceedsKr = 10_000.0, sourceRecordId = 7))
        val transactions = listOf(
            sell(1, "A", 10_000.0),
            buy(2, "KAND", 1_000.0),
            buy(3, "FORESLAGEN", 2_000.0, fee = 50.0),
            buy(4, "NIVA3", 3_000.0),
            buy(5, "NIVA5", 500.0), // annan nivå — inte bytets pengar
            buy(6, "UTAN", 500.0), // utan ISIN kan inte matchas
            buy(7, "KAND", 500.0, day = soldAt - 1) // före säljdagen — pengarna fanns inte
        )

        val pending = pending(watches, transactions, buyIsinByRecordId = mapOf(7L to "SEFORESLAGEN"))

        assertEquals(10_000.0 - 1_000.0 - 2_050.0 - 3_000.0, pending.getValue(3), 1e-9)
    }

    @Test
    fun `helt forbrukad likvid ger ingen post - ett kop storre an resten forbrukas helt utan att fora over`() {
        val watches = listOf(watch(1, proceedsKr = 1_000.0), watch(2, proceedsKr = 1_000.0, sellTransactionId = 2, soldAtEpochDay = soldAt + 1))
        val transactions = listOf(sell(1, "A", 1_000.0), sell(2, "A", 1_000.0, day = soldAt + 1), buy(3, "KAND", 1_500.0, day = soldAt + 2))

        val pending = pending(watches, transactions)

        // Köpet (1 500) tas av den äldsta bevakningen (1 000 → 0); de 500 kr över förs inte vidare —
        // den andra bevakningens 1 000 står kvar. Underräkning är ok, dubbelräkning aldrig.
        assertEquals(mapOf(3 to 1_000.0), pending)
    }

    @Test
    fun `ett kop forbrukas hogst en gang totalt aven med tva bevakningar pa samma niva`() {
        val watches = listOf(watch(2, proceedsKr = 3_000.0, sellTransactionId = 2, soldAtEpochDay = soldAt + 1), watch(1, proceedsKr = 3_000.0))
        val transactions = listOf(sell(1, "A", 3_000.0), sell(2, "A", 3_000.0, day = soldAt + 1), buy(3, "KAND", 2_000.0, day = soldAt + 2))

        val pending = pending(watches, transactions)

        // Äldst först: bevakning 1 tar köpet (3 000 → 1 000), bevakning 2 har 3 000 kvar. Summan 4 000 —
        // hade köpet räknats mot båda vore den 2 000.
        assertEquals(mapOf(3 to 4_000.0), pending)
    }

    @Test
    fun `ett kop som avslutat en annan bevakning forbrukar inte den oppnas likvid`() {
        // Byte 1 (nivå 3) stängdes av köp 3 som buyTransactionId; byte 2 på samma nivå väntar
        // fortfarande. Räknades köpet igen mot byte 2 blev likviden 0 och planen sålde en gång till.
        val closedByBuy = watch(1, proceedsKr = 2_000.0).copy(
            closedAtEpochDay = soldAt + 2,
            closeReason = SwitchWatchCloseReason.KOPT,
            boughtIsin = "SEKAND",
            buyTransactionId = 3
        )
        val open = watch(2, proceedsKr = 2_000.0, sellTransactionId = 2, soldAtEpochDay = soldAt + 1)
        val transactions = listOf(sell(1, "A", 2_000.0), sell(2, "A", 2_000.0, day = soldAt + 1), buy(3, "KAND", 2_000.0, day = soldAt + 2))

        assertEquals(mapOf(3 to 2_000.0), pending(listOf(closedByBuy, open), transactions))
    }

    @Test
    fun `likviden vager pa malnivan och i namnaren - ett finansierat gap foreslar inget nytt salj`() {
        // 6 000 kr kvar på nivå 5 efter ett sälj på 4 000 kr som väntar på köp (mål 40/60 nivå 3/5).
        // Utan likviden ser nivå 3 ut som 0 % och planen säljer 2 400 kr till; med den är
        // fördelningen redan 40/60 och planen tom.
        val holdings = listOf(holding("A", 6_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 5, fee = 1.0))
        val candidates = listOf(candidate("SEC", risk = 3, fee = 0.2, twelveMonthReturn = 0.1))
        val target = mapOf(3 to 0.4, 5 to 0.6)

        assertEquals(1, plan(holdings, metadataByIsin, candidates, target).size)
        assertTrue(SwitchPlanCalc.plan(holdings, metadataByIsin, candidates, target, pendingProceedsByLevel = mapOf(3 to 4_000.0)).isEmpty())
        assertTrue(SwitchPlanCalc.underweightedLevels(holdings, metadataByIsin, target, mapOf(3 to 4_000.0)).isEmpty())
    }

    @Test
    fun `likviden kan aldrig saljas - en overviktad niva med bara likvid ger ingen plan`() {
        // Allt innehav på nivå 3 (mål 100 % nivå 3) plus 4 000 kr likvid på väg till nivå 5: nivå 5
        // är överviktad men det finns ingen position att sälja där — likviden är inte en fond.
        val holdings = listOf(holding("A", 6_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 3, fee = 1.0))
        val candidates = listOf(candidate("SEC", risk = 3, fee = 0.2, twelveMonthReturn = 0.1))

        val plan = SwitchPlanCalc.plan(holdings, metadataByIsin, candidates, mapOf(3 to 1.0), pendingProceedsByLevel = mapOf(5 to 4_000.0))

        assertTrue(plan.isEmpty())
    }

    // --- preselectTargetLevel: förvald målnivå för en fristående bevakning (issue #150) ---

    /** Innehaven **efter** säljet: nivå 2, 3 och 5 (säljfondens nivå) med givna värden. */
    private fun afterSale(level2: Double, level3: Double, level5: Double): Pair<List<Holding>, Map<String, FundMetadata>> = listOf(holding("Två", level2, isin = "SE2"), holding("Tre", level3, isin = "SE3"), holding("Fem", level5, isin = "SE5")) to mapOf(
        "SE2" to metadata("SE2", risk = 2, fee = 0.3),
        "SE3" to metadata("SE3", risk = 3, fee = 0.3),
        "SE5" to metadata("SE5", risk = 5, fee = 0.3)
    )

    /**
     * Förvalet för ett fristående sälj som produktionen räknar det (`TargetLevelPreselection.preselect`):
     * den enda regeln [SwitchPlanCalc.preselectTargetLevel] på profilens förslag.
     */
    private fun preselect(
        holdings: List<Holding>,
        metadataByIsin: Map<String, FundMetadata>,
        target: Map<Int, Double>,
        sellLevel: Int?,
        proceedsKr: Double?,
        pendingProceedsByLevel: Map<Int, Double> = emptyMap()
    ): Int? = SwitchPlanCalc.preselectTargetLevel(
        planToLevel = null,
        profileSuggestion = SwitchPlanCalc.profileSuggestion(holdings, metadataByIsin, target, proceedsKr, pendingProceedsByLevel),
        sellLevel = sellLevel
    )

    @Test
    fun `forvalet med profil ar nivan med storst gap - egen likvid bara i totalen`() {
        // Mål 20/60/20 på nivå 2/3/5, 7 000 kr likvid ur ett sälj på nivå 5. Med likviden i totalen
        // (10 000 kr) saknar nivå 3 40 pp — störst. Utan den i totalen (3 000 kr) är ingen nivå
        // underviktad 5 pp och förvalet hade fallit tillbaka på säljfondens nivå.
        val (holdings, metadataByIsin) = afterSale(level2 = 500.0, level3 = 2_000.0, level5 = 500.0)
        val target = mapOf(2 to 0.2, 3 to 0.6, 5 to 0.2)

        assertEquals(3, preselect(holdings, metadataByIsin, target, sellLevel = 5, proceedsKr = 7_000.0))
    }

    @Test
    fun `profilens forslag ger samma niva som forvalet och gapet i procentenheter`() {
        // Samma regel som förvalet, med gapet — skärmen säger "40 procentenheter för lite där".
        val (holdings, metadataByIsin) = afterSale(level2 = 500.0, level3 = 2_000.0, level5 = 500.0)
        val target = mapOf(2 to 0.2, 3 to 0.6, 5 to 0.2)

        val suggestion = SwitchPlanCalc.profileSuggestion(holdings, metadataByIsin, target, proceedsKr = 7_000.0)!!

        assertEquals(3, suggestion.level)
        assertEquals(40.0, suggestion.gapPp, 1e-9)
        assertEquals(suggestion.level, preselect(holdings, metadataByIsin, target, sellLevel = 5, proceedsKr = 7_000.0))
        // Utan profil finns inget förslag — reserven (säljfondens nivå) hör bara till förvalet.
        assertEquals(null, SwitchPlanCalc.profileSuggestion(holdings, metadataByIsin, emptyMap(), proceedsKr = 7_000.0))
    }

    @Test
    fun `forvalet styrs inte av egen likvid pa saljfondens niva`() {
        // Mål 50/50 på nivå 3/5; säljet tog 5 000 kr ur nivå 5, som nu är mest underviktad. Låg den
        // egna likviden på nivå 5 hade gapet sett fyllt ut och förvalet blivit nivå 3 — likviden
        // hade då styrt bort pengarna från nivån de kom ifrån.
        val (holdings, metadataByIsin) = afterSale(level2 = 0.0, level3 = 4_000.0, level5 = 1_000.0)
        val target = mapOf(3 to 0.5, 5 to 0.5)

        assertEquals(5, preselect(holdings, metadataByIsin, target, sellLevel = null, proceedsKr = 5_000.0))
        assertEquals(
            3,
            preselect(holdings, metadataByIsin, target, sellLevel = null, proceedsKr = null, pendingProceedsByLevel = mapOf(5 to 5_000.0))
        )
    }

    @Test
    fun `andra bevakningars likvid raknas pa sina nivaer i forvalet`() {
        // Nivå 3 är redan på väg att fyllas av ett annat byte (BYT-3) — förvalet går till nivå 2.
        val (holdings, metadataByIsin) = afterSale(level2 = 1_000.0, level3 = 1_000.0, level5 = 4_000.0)
        val target = mapOf(2 to 0.3, 3 to 0.4, 5 to 0.3)

        assertEquals(3, preselect(holdings, metadataByIsin, target, sellLevel = 5, proceedsKr = 2_000.0))
        assertEquals(
            2,
            preselect(holdings, metadataByIsin, target, sellLevel = 5, proceedsKr = 2_000.0, pendingProceedsByLevel = mapOf(3 to 2_000.0))
        )
    }

    @Test
    fun `utan riskprofil ar forvalet saljfondens egen niva`() {
        val (holdings, metadataByIsin) = afterSale(level2 = 500.0, level3 = 2_000.0, level5 = 500.0)

        assertEquals(5, preselect(holdings, metadataByIsin, emptyMap(), sellLevel = 5, proceedsKr = 7_000.0))
        // Okänd nivå och ingen profil: inget förval, aldrig en gissad nivå (ANA-4).
        assertEquals(null, preselect(holdings, metadataByIsin, emptyMap(), sellLevel = null, proceedsKr = 7_000.0))
    }

    @Test
    fun `utan underviktad niva ar forvalet saljfondens egen niva`() {
        // Allt ligger inom MIN_GAP_PP från målet — ingen nivå behöver pengarna mer än en annan.
        val (holdings, metadataByIsin) = afterSale(level2 = 0.0, level3 = 5_000.0, level5 = 4_800.0)

        assertEquals(5, preselect(holdings, metadataByIsin, mapOf(3 to 0.5, 5 to 0.5), sellLevel = 5, proceedsKr = 200.0))
    }

    @Test
    fun `bevakning ur bytesplanen behaller planens toLevel`() {
        val (holdings, metadataByIsin) = afterSale(level2 = 500.0, level3 = 2_000.0, level5 = 500.0)
        val target = mapOf(2 to 0.2, 3 to 0.6, 5 to 0.2)

        assertEquals(
            2,
            SwitchPlanCalc.preselectTargetLevel(
                planToLevel = 2,
                profileSuggestion = SwitchPlanCalc.profileSuggestion(holdings, metadataByIsin, target, proceedsKr = 7_000.0),
                sellLevel = 5
            )
        )
    }

    @Test
    fun `regeln - planradens niva fore profilens fore saljfondens`() {
        val gap = SwitchPlanCalc.LevelGap(level = 3, gapPp = 12.0)
        assertEquals(2, SwitchPlanCalc.preselectTargetLevel(planToLevel = 2, profileSuggestion = gap, sellLevel = 5))
        assertEquals(3, SwitchPlanCalc.preselectTargetLevel(planToLevel = null, profileSuggestion = gap, sellLevel = 5))
        assertEquals(5, SwitchPlanCalc.preselectTargetLevel(planToLevel = null, profileSuggestion = null, sellLevel = 5))
        assertEquals(null, SwitchPlanCalc.preselectTargetLevel(planToLevel = null, profileSuggestion = null, sellLevel = null))
    }
}
