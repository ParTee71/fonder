package se.partee71.fonder.ui.diagram

import com.patrykandpatrick.vico.core.common.data.MutableExtraStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PriceRangeProvider] — Vicos standardintervall (`CartesianLayerRangeProvider.auto()`)
 * tvingar alltid y-axeln till att inkludera noll, vilket klämmer ihop fondkursers (alltid
 * positiva) faktiska rörelse mot en avlägsen nollinje. Testar att vi i stället pads runt
 * kursens egna min/max.
 */
class FundLineChartTest {

    private val extraStore = MutableExtraStore()

    @Test
    fun `padar runt kursintervallet i stallet for att alltid inkludera noll`() {
        // En fond som pendlat mellan 180 och 220 kr ska inte få en y-axel som sträcker sig
        // ner till noll — det är hela poängen med bugfixen.
        val minY = PriceRangeProvider.getMinY(180.0, 220.0, extraStore)
        val maxY = PriceRangeProvider.getMaxY(180.0, 220.0, extraStore)

        assertTrue("y-axeln ska inte tvingas till noll för en fond med kurser långt över noll", minY > 0.0)
        assertEquals(176.0, minY, 1e-9)
        assertEquals(224.0, maxY, 1e-9)
    }

    @Test
    fun `konstant kurs far anda lite marginal sa linjen inte ligger pa axelkanten`() {
        val minY = PriceRangeProvider.getMinY(150.0, 150.0, extraStore)
        val maxY = PriceRangeProvider.getMaxY(150.0, 150.0, extraStore)

        assertEquals(135.0, minY, 1e-9)
        assertEquals(165.0, maxY, 1e-9)
    }

    @Test
    fun `noll som bade min och max ger fortfarande ett synligt intervall`() {
        // Orealistiskt för en fondkurs, men ska inte krascha eller ge ett tomt intervall.
        val minY = PriceRangeProvider.getMinY(0.0, 0.0, extraStore)
        val maxY = PriceRangeProvider.getMaxY(0.0, 0.0, extraStore)

        assertTrue(minY < maxY)
    }

    @Test
    fun `avkastningsaxeln inkluderar alltid nollinjen aven nar kurvan bara ar positiv`() {
        // Hela poängen med HEM-9:s procentläge: utan nollinjen ser +3..+8 % ut precis som
        // −8..−3 %, och diagrammet slutar svara på den första fråga man ställer till det.
        val minY = ReturnRangeProvider.getMinY(0.03, 0.08, extraStore)
        val maxY = ReturnRangeProvider.getMaxY(0.03, 0.08, extraStore)

        assertTrue("nollinjen ska rymmas i intervallet", minY <= 0.0)
        assertEquals(-0.008, minY, 1e-9)
        assertEquals(0.088, maxY, 1e-9)
    }

    @Test
    fun `avkastningsaxeln inkluderar nollinjen aven nar kurvan bara ar negativ`() {
        val minY = ReturnRangeProvider.getMinY(-0.20, -0.05, extraStore)
        val maxY = ReturnRangeProvider.getMaxY(-0.20, -0.05, extraStore)

        assertTrue("nollinjen ska rymmas i intervallet", maxY >= 0.0)
        assertEquals(-0.22, minY, 1e-9)
        assertEquals(0.02, maxY, 1e-9)
    }

    @Test
    fun `platt avkastningskurva far anda ett synligt intervall`() {
        val minY = ReturnRangeProvider.getMinY(0.0, 0.0, extraStore)
        val maxY = ReturnRangeProvider.getMaxY(0.0, 0.0, extraStore)

        assertTrue(minY < maxY)
        assertEquals(-0.01, minY, 1e-9)
        assertEquals(0.01, maxY, 1e-9)
    }

    @Test
    fun `kursaxeln tvingas fortfarande inte till noll`() {
        // Regressionsvakt mot att procentläget (issue #96) skulle "rätta" kronintervallet till
        // att alltid inkludera noll — det var exakt buggen #49 fixade.
        assertTrue(PriceRangeProvider.getMinY(180.0, 220.0, extraStore) > 0.0)
    }
}
