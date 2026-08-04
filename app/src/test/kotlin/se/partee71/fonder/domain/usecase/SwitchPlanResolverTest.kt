package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import java.time.LocalDate

/**
 * Uppslaget av den inspelade bytesplanen (HEM-8), utbrutet ur `HemViewModel` i issue #85 så
 * Fonddetaljs bytesavsnitt (ANA-10) läser samma plan med samma regler. Testerna vaktar de
 * regler som gjorde planen verkningslös när de saknades (issue #75): färskhet, prefixkapning
 * och att en rad utan komplett metadata aldrig visas.
 */
class SwitchPlanResolverTest {

    private val today = LocalDate.of(2026, 8, 4)

    private fun metadata(isin: String, name: String, risk: Int?, fee: Double?) = FundMetadata(
        isin = isin, name = name, orderbookId = "ob-$isin", totalFee = fee, managementFee = fee,
        category = null, fundType = null, companyName = null, risk = risk, indexFund = false,
        startDateEpochDay = null, minimumBuy = null, tags = emptyList(),
    )

    private fun record(
        planIndex: Int,
        sell: String,
        buy: String,
        epochDay: Long = today.toEpochDay(),
        kind: SuggestionKind = SuggestionKind.RISK_PLAN,
    ) = SuggestionRecord(
        id = planIndex + 1L,
        suggestedAtEpochDay = epochDay,
        planIndex = planIndex,
        sellIsin = sell,
        buyIsin = buy,
        sellNavAtSuggestion = 100.0,
        buyNavAtSuggestion = 90.0,
        switchValueKr = 4000.0,
        kind = kind,
    )

    private val metadataByIsin = mapOf(
        "A" to metadata("A", "Fond A", risk = 6, fee = 1.4),
        "B" to metadata("B", "Fond B", risk = 3, fee = 0.4),
        "C" to metadata("C", "Fond C", risk = 4, fee = 0.6),
    )

    @Test
    fun `resolvar en plan i ISK eller KF`() {
        val plan = SwitchPlanResolver.resolve(AccountType.ISK_KF, listOf(record(0, "A", "B")), metadataByIsin, today)

        val suggestion = plan.single()
        assertEquals("Fond A", suggestion.sellFundName)
        assertEquals("Fond B", suggestion.buyFundName)
        assertEquals(6, suggestion.fromLevel)
        assertEquals(3, suggestion.toLevel)
        assertEquals(-1.0, suggestion.feeDeltaPercent, 0.0001)
        assertEquals(4000.0, suggestion.switchValueKr)
    }

    @Test
    fun `ger ingen plan utan ISK eller KF`() {
        assertTrue(SwitchPlanResolver.resolve(AccountType.DEPA_AF, listOf(record(0, "A", "B")), metadataByIsin, today).isEmpty())
        assertTrue(SwitchPlanResolver.resolve(null, listOf(record(0, "A", "B")), metadataByIsin, today).isEmpty())
    }

    @Test
    fun `ger ingen plan nar inspelningen ar aldre an TTL`() {
        val old = record(0, "A", "B", epochDay = today.minusDays(SwitchPlanCalc.PLAN_TTL_DAYS + 1).toEpochDay())

        assertTrue(SwitchPlanResolver.resolve(AccountType.ISK_KF, listOf(old), metadataByIsin, today).isEmpty())
    }

    @Test
    fun `kapar planen vid forsta luckan i stallet for att presentera ett fristaende byte`() {
        // Byte 1 kan inte slås upp (okänd ISIN) → byte 2 får inte visas som om det vore nästa steg.
        val batch = listOf(record(0, "A", "B"), record(1, "A", "OKÄND"), record(2, "A", "C"))

        val plan = SwitchPlanResolver.resolve(AccountType.ISK_KF, batch, metadataByIsin, today)

        assertEquals(listOf(0), plan.map { it.planIndex })
    }

    @Test
    fun `ger ingen plan alls nar forsta bytet inte gar att sla upp`() {
        val batch = listOf(record(0, "A", "OKÄND"), record(1, "A", "C"))

        assertTrue(SwitchPlanResolver.resolve(AccountType.ISK_KF, batch, metadataByIsin, today).isEmpty())
    }

    @Test
    fun `utelamnar rader vars metadata saknar risknniva eller avgift`() {
        val incomplete = metadataByIsin + ("B" to metadata("B", "Fond B", risk = null, fee = 0.4))

        assertTrue(SwitchPlanResolver.resolve(AccountType.ISK_KF, listOf(record(0, "A", "B")), incomplete, today).isEmpty())
    }

    @Test
    fun `avgiftsbyten ingar aldrig i planen — inte ens som enda raden`() {
        // Det dygn planen inte gav något (ingen nivå avviker tillräckligt) men
        // avgiftsskanningen spelade in en rad är det vanligaste fallet, inte ett hörnfall
        // (issue #91). Utan filtret hade raden visats som "1. Sälj A → Köp C" i en plan den
        // aldrig ingick i — och att följa den flyttar inte portföljen mot målfördelningen.
        val batch = listOf(record(0, "A", "C", kind = SuggestionKind.FEE))

        assertTrue(SwitchPlanResolver.resolve(AccountType.ISK_KF, batch, metadataByIsin, today).isEmpty())
    }

    @Test
    fun `en avgiftsrad forskjuter aldrig planens rangordning`() {
        val batch = listOf(record(0, "A", "C", kind = SuggestionKind.FEE), record(0, "A", "B"))

        val plan = SwitchPlanResolver.resolve(AccountType.ISK_KF, batch, metadataByIsin, today)

        assertEquals(listOf("B"), plan.map { it.buyIsin })
        assertEquals(listOf(0), plan.map { it.planIndex })
    }

    @Test
    fun `forFund ger bytena som ror fonden i bada riktningarna`() {
        val plan = SwitchPlanResolver.resolve(
            AccountType.ISK_KF,
            listOf(record(0, "A", "B"), record(1, "C", "A")),
            metadataByIsin,
            today,
        )

        assertEquals(listOf(0, 1), SwitchPlanResolver.forFund(plan, "A").map { it.planIndex })
        assertEquals(listOf(0), SwitchPlanResolver.forFund(plan, "B").map { it.planIndex })
        assertTrue(SwitchPlanResolver.forFund(plan, "OKÄND").isEmpty())
    }

    @Test
    fun `forFund utan isin ger ingenting — fonden gar inte att koppla till en inspelad rad`() {
        val plan = SwitchPlanResolver.resolve(AccountType.ISK_KF, listOf(record(0, "A", "B")), metadataByIsin, today)

        assertTrue(SwitchPlanResolver.forFund(plan, null).isEmpty())
    }
}
