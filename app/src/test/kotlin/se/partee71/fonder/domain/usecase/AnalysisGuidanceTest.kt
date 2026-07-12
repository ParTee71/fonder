package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.usecase.AnalysisGuidance.GuidanceKey
import se.partee71.fonder.domain.usecase.FundAnalysisCalc.DistanceFromHighSignal
import se.partee71.fonder.domain.usecase.FundAnalysisCalc.MomentumSignal
import se.partee71.fonder.domain.usecase.FundAnalysisCalc.SignalLevel
import se.partee71.fonder.domain.usecase.FundAnalysisCalc.TrendSignal

class AnalysisGuidanceTest {

    private fun keyFigures(gavFraction: Double? = 0.2) = FundAnalysisCalc.KeyFigures(
        periodReturns = FundAnalysisCalc.Period.entries.map { FundAnalysisCalc.PeriodReturn(it, amount = 10.0, fraction = 0.05) },
        cagr = 0.05,
        currentNav = 120.0,
        gavPerShare = 100.0,
        gavFraction = gavFraction,
        portfolioShareFraction = 0.25,
        annualizedVolatility = 0.18,
        sharpeRatio = 0.8,
    )

    private fun analysis(
        distanceLevel: SignalLevel? = null,
        distanceFraction: Double = -0.15,
        trendLevel: SignalLevel? = null,
        momentumLevel: SignalLevel? = null,
        gavFraction: Double? = 0.2,
        status: SignalLevel? = SignalLevel.GUL,
    ) = FundAnalysisCalc.Analysis(
        keyFigures = keyFigures(gavFraction),
        distanceFromHigh = distanceLevel?.let { DistanceFromHighSignal(it, distanceFraction) },
        trend = trendLevel?.let { TrendSignal(it) },
        momentum = momentumLevel?.let { MomentumSignal(it, -6.0) },
        status = status,
        profitTake = null,
    )

    @Test
    fun `under toppen men plus mot GAV ger uppmuntrande kontext`() {
        val keys = AnalysisGuidance.guidanceFor(
            analysis(distanceLevel = SignalLevel.GUL, gavFraction = 0.06, status = SignalLevel.GUL),
        )
        assertTrue(keys.contains(GuidanceKey.NEDGANG_MEN_PLUS_MOT_GAV))
    }

    @Test
    fun `under toppen men minus mot GAV ger inte plus-mot-GAV-kontext`() {
        val keys = AnalysisGuidance.guidanceFor(
            analysis(distanceLevel = SignalLevel.ROD, gavFraction = -0.05, status = SignalLevel.ROD),
        )
        assertTrue(!keys.contains(GuidanceKey.NEDGANG_MEN_PLUS_MOT_GAV))
    }

    @Test
    fun `djup nedgang ger tidshorisont-kontext`() {
        val keys = AnalysisGuidance.guidanceFor(
            analysis(distanceLevel = SignalLevel.ROD, distanceFraction = -0.25, gavFraction = -0.1, status = SignalLevel.ROD),
        )
        assertEquals(listOf(GuidanceKey.DJUP_NEDGANG_TIDSHORISONT), keys)
    }

    @Test
    fun `under 200-dagarssnittet ger trend-kontext`() {
        val keys = AnalysisGuidance.guidanceFor(analysis(trendLevel = SignalLevel.GUL, status = SignalLevel.GUL))
        assertTrue(keys.contains(GuidanceKey.UNDER_TREND_INTE_SALJBUD))
    }

    @Test
    fun `svagare an portfoljsnittet ger portfolj-kontext`() {
        val keys = AnalysisGuidance.guidanceFor(analysis(momentumLevel = SignalLevel.GUL, status = SignalLevel.GUL))
        assertTrue(keys.contains(GuidanceKey.SVAG_MOT_PORTFOLJ))
    }

    @Test
    fun `gront lage ger lugnt-kontext`() {
        val keys = AnalysisGuidance.guidanceFor(
            analysis(
                distanceLevel = SignalLevel.GRON,
                trendLevel = SignalLevel.GRON,
                momentumLevel = SignalLevel.GRON,
                status = SignalLevel.GRON,
            ),
        )
        assertEquals(listOf(GuidanceKey.LUGNT_LAGE), keys)
    }

    @Test
    fun `otillracklig data ger tom vagledning i stallet for gissning`() {
        // status == null betyder att ingen signal kunde beräknas (ANA-4).
        val keys = AnalysisGuidance.guidanceFor(analysis(status = null))
        assertTrue(keys.isEmpty())
    }

    @Test
    fun `flera signaler kombineras i fast ordning`() {
        val keys = AnalysisGuidance.guidanceFor(
            analysis(
                distanceLevel = SignalLevel.ROD,
                distanceFraction = -0.22,
                trendLevel = SignalLevel.GUL,
                momentumLevel = SignalLevel.GUL,
                gavFraction = 0.05,
                status = SignalLevel.ROD,
            ),
        )
        assertEquals(
            listOf(
                GuidanceKey.NEDGANG_MEN_PLUS_MOT_GAV,
                GuidanceKey.DJUP_NEDGANG_TIDSHORISONT,
                GuidanceKey.UNDER_TREND_INTE_SALJBUD,
                GuidanceKey.SVAG_MOT_PORTFOLJ,
            ),
            keys,
        )
    }
}
