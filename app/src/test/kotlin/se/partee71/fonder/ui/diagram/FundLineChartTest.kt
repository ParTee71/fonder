package se.partee71.fonder.ui.diagram

import com.patrykandpatrick.vico.compose.cartesian.data.LineCartesianLayerModel
import com.patrykandpatrick.vico.compose.common.data.MutableExtraStore
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
    fun `avkastningsaxeln pads runt kurvans egna min-max, utan tvingad nollinje`() {
        // Samma intervallregel som fondens kursdiagram (issue #96, uppföljning): en portfölj som
        // rört sig mellan +3 % och +8 % ska fylla diagramhöjden, inte tryckas ihop mot en nolla
        // som ligger utanför den visade perioden. Tecknet syns på axeletiketterna i stället.
        val minY = PriceRangeProvider.getMinY(0.03, 0.08, extraStore)
        val maxY = PriceRangeProvider.getMaxY(0.03, 0.08, extraStore)

        assertTrue("nollinjen ska inte tvingas in", minY > 0.0)
        assertEquals(0.025, minY, 1e-9)
        assertEquals(0.085, maxY, 1e-9)
    }

    @Test
    fun `y-intervallet rymmer snittet nar det ligger utanfor kursens min-max`() {
        // ANA-14: kursen har rört sig mellan 380 och 390 kr, snittet ligger kvar på 360 — samma
        // lager, så intervallet räknas över båda och snittlinjen klipps inte bort.
        val price = (0L until 10L).map { it to 380.0 + it }
        val average = (0L until 10L).map { it to 360.0 }
        val layer = chartModel(listOf(price, average)).models.single() as LineCartesianLayerModel

        assertEquals(360.0, layer.minY, 1e-9)
        assertEquals(389.0, layer.maxY, 1e-9)
        val minY = PriceRangeProvider.getMinY(layer.minY, layer.maxY, extraStore)
        val maxY = PriceRangeProvider.getMaxY(layer.minY, layer.maxY, extraStore)
        assertTrue("snittet ska ligga inom y-axeln", minY < 360.0 && maxY > 389.0)
    }
}
