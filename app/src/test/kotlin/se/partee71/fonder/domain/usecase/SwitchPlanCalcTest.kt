package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.Holding

class SwitchPlanCalcTest {

    private fun fund(fundId: String, isin: String? = "SE$fundId") = Fund(fundId = fundId, name = fundId, isin = isin)

    private fun holding(fundId: String, value: Double?, isin: String? = "SE$fundId") =
        Holding(fund = fund(fundId, isin), netShares = 1.0, netInvested = 0.0, currentValue = value)

    private fun metadata(isin: String, risk: Int?, fee: Double?) = FundMetadata(
        isin = isin, name = isin, orderbookId = isin, totalFee = fee, managementFee = fee,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
    )

    private fun candidate(isin: String, risk: Int, fee: Double, twelveMonthReturn: Double) =
        SwitchPlanCalc.Candidate(metadata(isin, risk, fee), twelveMonthReturn)

    @Test
    fun `tom malfordelning ger tom plan`() {
        val plan = SwitchPlanCalc.plan(emptyList(), emptyMap(), emptyList(), emptyMap())

        assertTrue(plan.switches.isEmpty())
        assertEquals(0.0, plan.gapClosedPp, 1e-9)
    }

    @Test
    fun `portfolj redan i linje med malet ger tom plan`() {
        val holdings = listOf(holding("A", 10_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 4, fee = 0.5))

        val plan = SwitchPlanCalc.plan(holdings, metadataByIsin, emptyList(), mapOf(4 to 1.0))

        assertTrue(plan.switches.isEmpty())
    }

    @Test
    fun `enkelt byte fran overviktad till underviktad niva`() {
        val holdings = listOf(holding("A", 10_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 5, fee = 1.0))
        val candidates = listOf(candidate("SEB", risk = 3, fee = 0.3, twelveMonthReturn = 0.1))

        val plan = SwitchPlanCalc.plan(holdings, metadataByIsin, candidates, mapOf(3 to 1.0))

        val switch = plan.switches.single()
        assertEquals("A", switch.sellFund.fundId)
        assertEquals(10_000.0, switch.sellValueKr, 1e-9)
        assertEquals("SEB", switch.buyIsin)
        assertEquals(5, switch.fromLevel)
        assertEquals(3, switch.toLevel)
        assertEquals(0.3 - 1.0, switch.feeDelta, 1e-9)
        assertEquals(100.0, plan.gapClosedPp, 1e-9)
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
            "SEB" to metadata("SEB", risk = 5, fee = 1.0),
        )
        val candidates = listOf(
            candidate("SEC3", risk = 3, fee = 0.2, twelveMonthReturn = 0.15),
            candidate("SEC2", risk = 2, fee = 0.15, twelveMonthReturn = 0.05),
        )

        val plan = SwitchPlanCalc.plan(holdings, metadataByIsin, candidates, mapOf(2 to 0.3, 3 to 0.7))

        assertEquals(2, plan.switches.size)
        assertEquals("A" to 3, plan.switches[0].sellFund.fundId to plan.switches[0].toLevel)
        assertEquals("B" to 2, plan.switches[1].sellFund.fundId to plan.switches[1].toLevel)
        // Byte 1 fyller nivå 3 helt (5 000 kr), byte 2 begränsas till nivå 2:s gap (3 000 kr av
        // B:s 5 000) i stället för att sälja hela positionen — se överskjutningstesterna nedan.
        assertEquals(5_000.0, plan.switches[0].sellValueKr, 1e-9)
        assertEquals(3_000.0, plan.switches[1].sellValueKr, 1e-9)
        assertEquals(80.0, plan.gapClosedPp, 1e-9)
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
            "SEHOG" to metadata("SEHOG", risk = 5, fee = 1.0),
        )
        val candidates = listOf(candidate("SEC3", risk = 3, fee = 0.2, twelveMonthReturn = 0.1))

        val plan = SwitchPlanCalc.plan(holdings, metadataByIsin, candidates, mapOf(3 to 0.5, 5 to 0.5))

        val switch = plan.switches.single()
        assertEquals("Hog", switch.sellFund.fundId)
        assertEquals(1_000.0, switch.sellValueKr, 1e-9)
        // Ett enda byte som stänger gapet helt — ingen andra rad som säljer tillbaka.
        assertEquals(10.0, plan.gapClosedPp, 1e-9)
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
            candidate("SEC2", risk = 2, fee = 0.15, twelveMonthReturn = 0.05),
        )

        val plan = SwitchPlanCalc.plan(holdings, metadataByIsin, candidates, mapOf(2 to 0.2, 3 to 0.3, 5 to 0.5))

        assertEquals(2, plan.switches.size)
        assertTrue("båda bytena säljer ur samma position", plan.switches.all { it.sellFund.fundId == "Enda" })
        assertEquals(3_000.0, plan.switches[0].sellValueKr, 1e-9)
        assertEquals(3, plan.switches[0].toLevel)
        assertEquals(2_000.0, plan.switches[1].sellValueKr, 1e-9)
        assertEquals(2, plan.switches[1].toLevel)
    }

    @Test
    fun `tak pa antal byten per plan aven om fler skulle behovas`() {
        val holdings = (1..4).map { i -> holding("H$i", 2_500.0) }
        val metadataByIsin = (1..4).associate { i -> "SEH$i" to metadata("SEH$i", risk = 6, fee = (5 - i).toDouble()) }
        val candidates = (1..4).map { i -> candidate("SEC$i", risk = 1, fee = 0.1, twelveMonthReturn = 0.1) }

        val plan = SwitchPlanCalc.plan(holdings, metadataByIsin, candidates, mapOf(1 to 1.0))

        assertEquals(SwitchPlanCalc.MAX_SWITCHES_PER_PLAN, plan.switches.size)
    }

    @Test
    fun `sarjkandidat inom en niva ar den med hogst avgift`() {
        val holdings = listOf(holding("Billig", 1_000.0, isin = "SEBILLIG"), holding("Dyr", 1_000.0, isin = "SEDYR"))
        val metadataByIsin = mapOf(
            "SEBILLIG" to metadata("SEBILLIG", risk = 5, fee = 0.5),
            "SEDYR" to metadata("SEDYR", risk = 5, fee = 2.0),
        )
        val candidates = listOf(candidate("SEC", risk = 3, fee = 0.2, twelveMonthReturn = 0.1))

        val plan = SwitchPlanCalc.plan(holdings, metadataByIsin, candidates, mapOf(3 to 1.0))

        assertEquals("Dyr", plan.switches.first().sellFund.fundId)
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
            candidate("C8", risk = 3, fee = 0.5, twelveMonthReturn = 0.01),
        )

        val plan = SwitchPlanCalc.plan(holdings, metadataByIsin, candidates, mapOf(3 to 1.0))

        assertEquals("HOG_BILLIG", plan.switches.single().buyIsin)
    }

    @Test
    fun `innehav utan kand risknivastraffas ur planen och rakas inte in i totalen`() {
        val holdings = listOf(holding("OkandRisk", 5_000.0, isin = "SEOKAND"), holding("KandRisk", 5_000.0, isin = "SEKAND"))
        val metadataByIsin = mapOf(
            "SEOKAND" to metadata("SEOKAND", risk = null, fee = 1.0),
            "SEKAND" to metadata("SEKAND", risk = 5, fee = 1.0),
        )
        val candidates = listOf(candidate("SEC", risk = 3, fee = 0.2, twelveMonthReturn = 0.1))

        val plan = SwitchPlanCalc.plan(holdings, metadataByIsin, candidates, mapOf(3 to 1.0))

        val switch = plan.switches.single()
        assertEquals("KandRisk", switch.sellFund.fundId)
        // Bara det kända innehavets värde räknas som portföljbas — inte 10 000.
        assertEquals(5_000.0, switch.sellValueKr, 1e-9)
    }

    @Test
    fun `ingen kopkandidat for underviktad niva ger en tom plan`() {
        val holdings = listOf(holding("A", 10_000.0))
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 5, fee = 1.0))

        val plan = SwitchPlanCalc.plan(holdings, metadataByIsin, emptyList(), mapOf(3 to 1.0))

        assertTrue(plan.switches.isEmpty())
    }

    @Test
    fun `innehav utan aktuellt varde eller isin exkluderas`() {
        val holdings = listOf(
            holding("UtanVarde", value = null, isin = "SEA"),
            holding("UtanIsin", value = 1_000.0, isin = null),
        )
        val metadataByIsin = mapOf("SEA" to metadata("SEA", risk = 5, fee = 1.0))

        val plan = SwitchPlanCalc.plan(holdings, metadataByIsin, listOf(candidate("SEC", 3, 0.2, 0.1)), mapOf(3 to 1.0))

        assertTrue(plan.switches.isEmpty())
    }

    @Test
    fun `en avvikelse under MIN_GAP_PP ger ingen plan`() {
        // 4,9 % avvikelse — precis under tröskeln.
        val holdings = listOf(holding("A", 9_510.0, isin = "SEA"), holding("B", 490.0, isin = "SEB"))
        val metadataByIsin = mapOf(
            "SEA" to metadata("SEA", risk = 4, fee = 0.5),
            "SEB" to metadata("SEB", risk = 3, fee = 0.5),
        )

        val plan = SwitchPlanCalc.plan(holdings, metadataByIsin, listOf(candidate("SEC", 3, 0.2, 0.1)), mapOf(4 to 1.0))

        assertTrue(plan.switches.isEmpty())
    }
}
