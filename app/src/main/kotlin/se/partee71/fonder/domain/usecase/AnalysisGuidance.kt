package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.usecase.FundAnalysisCalc.SignalLevel

/**
 * Härleder pedagogisk **kontext** ur en färdig [FundAnalysisCalc.Analysis] (issue #22, ANA-6) —
 * hjälper en användare med liten ekonomisk kunskap att förstå vad signalerna betyder och,
 * framför allt, vad de *inte* betyder. Ren, testbar domänlogik: den bär bara [GuidanceKey]-enum,
 * ingen svensk text (den komponeras i UI-lagret, samma princip som [FundAnalysisCalc] och
 * `AnalysisStatus.kt`). Appen ger aldrig ett köp-/säljråd (ANA-3) — nycklarna beskriver ett
 * *sammanhang*, inte en handling.
 *
 * Ingen ny persisterad data: allt härleds ur redan lagrad kurs-/transaktionsdata via
 * [FundAnalysisCalc]. Otillräcklig data (t.ex. [FundAnalysisCalc.Analysis.status] == null) ger en
 * tom lista i stället för gissad vägledning — samma princip som ANA-4.
 */
object AnalysisGuidance {

    enum class GuidanceKey {
        /** Fonden ligger under toppen men fortfarande i plus mot ditt snittpris (GAV). */
        NEDGANG_MEN_PLUS_MOT_GAV,

        /** Djup nedgång (röd avståndssignal) — säljer du nu låser du in nedgången; tidshorisonten spelar roll. */
        DJUP_NEDGANG_TIDSHORISONT,

        /** Kursen ligger under 200-dagarssnittet — ett svaghetstecken, inte ett säljbud. */
        UNDER_TREND_INTE_SALJBUD,

        /** Fonden har utvecklats svagare än portföljsnittet senaste 3 månaderna. */
        SVAG_MOT_PORTFOLJ,

        /** Inga signaler triggade — ett lugnt läge, inget som kräver åtgärd. */
        LUGNT_LAGE,
    }

    /**
     * Returnerar kontextnycklarna för [analysis], i fast ordning (mest akut nedgångskontext
     * först, lugnt läge sist). Tom lista om analysen saknar en beräknad status (otillräcklig
     * data) — då finns inget att sätta i sammanhang.
     */
    fun guidanceFor(analysis: FundAnalysisCalc.Analysis): List<GuidanceKey> {
        if (analysis.status == null) return emptyList()

        val keys = mutableListOf<GuidanceKey>()
        val distance = analysis.distanceFromHigh
        val belowHigh = distance != null && distance.level != SignalLevel.GRON
        val gavFraction = analysis.keyFigures.gavFraction

        if (belowHigh && gavFraction != null && gavFraction > 0.0) {
            keys += GuidanceKey.NEDGANG_MEN_PLUS_MOT_GAV
        }
        if (distance != null && distance.level == SignalLevel.ROD) {
            keys += GuidanceKey.DJUP_NEDGANG_TIDSHORISONT
        }
        if (analysis.trend?.level == SignalLevel.GUL) {
            keys += GuidanceKey.UNDER_TREND_INTE_SALJBUD
        }
        if (analysis.momentum?.level == SignalLevel.GUL) {
            keys += GuidanceKey.SVAG_MOT_PORTFOLJ
        }
        if (keys.isEmpty() && analysis.status == SignalLevel.GRON) {
            keys += GuidanceKey.LUGNT_LAGE
        }
        return keys
    }
}
