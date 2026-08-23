package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import java.time.LocalDate

/**
 * Räknar ut vad de bevakade alternativen gjort sedan säljdagen (ANA-12, issue #114) — ren,
 * testbar domänlogik utan nätverk eller Room, precis som [SwitchPlanCalc].
 *
 * Frågan skärmen ska besvara är **inte** "vilken fond har gått bäst i år" utan "vad hade hänt
 * med mina pengar om de legat i den här fonden sedan jag sålde". Nollpunkten är därför alltid
 * kandidatens NAV på säljdagen ([SwitchWatchCandidate.navAtStart]), inte periodens början i
 * diagrammet — annars hade väntperiodens faktiska kostnad blandats ihop med fondens historik.
 */
object SwitchWatchCalc {

    /**
     * Tak på antal samtidigt bevakade alternativ. Inte en matematisk gräns utan en kostnads-
     * och beslutsgräns: varje kandidat kostar ett historikanrop mot en odokumenterad källa
     * (TP-14) varje gång skärmen öppnas, och en lista på tio fonder är inte ett beslutsunderlag
     * utan en ny sökning (samma beteenderesonemang som `SwitchPlanCalc.MAX_SWITCHES_PER_PLAN`).
     */
    const val MAX_CANDIDATES = 5

    /**
     * Så många kandidater föreslås automatiskt när bevakningen fylls (ANA-13) — resten av
     * utrymmet upp till [MAX_CANDIDATES] är användarens eget. Samma antal som bytesplanen visar
     * byten: appens förslag ska vara ett urval att välja *ur*, inte en lista att gå igenom.
     */
    const val AUTO_CANDIDATES = 3

    /**
     * Hur länge en bevakning är öppen utan att röras. Ett fondbyte går på T+1–T+3 och tar med
     * marginal för helger och röda dagar aldrig två veckor; en bevakning som legat orörd längre
     * beskriver ett läge som inte finns kvar. Samma princip som `SwitchPlanCalc.PLAN_TTL_DAYS`:
     * ett gammalt läge är fel på ett sätt gammal cache inte är. Bevakningen stängs som utgången
     * — den **raderas aldrig**, den bär vad användaren faktiskt gjorde.
     */
    const val WATCH_TTL_DAYS = 14L

    /**
     * Hur långt före säljdagen kandidathistoriken hämtas. Nollpunkten måste finnas *i* serien
     * för att gå att ankra, och en bevakning som startas ur en gammal säljrad (SLD-5) kan ha en
     * säljdag som ligger på en helg eller en röd dag — fönstret ska överleva det utan att
     * backfilla en historik appen ändå aldrig haft för fonden.
     */
    const val ANCHOR_LOOKBACK_DAYS = 10L

    /**
     * Vad en bevakad kandidat gjort sedan säljdagen.
     *
     * @param changeFraction utvecklingen som andel (0,032 = 3,2 %), null när nollpunkten eller
     *   den senaste kursen saknas — raden visas då som ej utvärderad, aldrig som 0 % (en nolla
     *   läses som "stod still", vilket är ett påstående appen inte kan göra, ANA-4-principen).
     * @param changeKr samma utveckling i kronor på det bevakade beloppet, null när beloppet
     *   inte är känt. Procenten kan alltså finnas utan kronorna — de är två olika mått, inte
     *   samma tal i två enheter.
     * @param partial sant när nollpunkten inte kunde ankras på säljdagen utan på en senare dag
     *   (ingen kurs fanns just då) — utvecklingen mäts då över ett kortare fönster än
     *   väntperioden, och det ska synas i stället för att tigas ihjäl (samma princip som ANA-11).
     */
    data class CandidateOutcome(
        val candidate: SwitchWatchCandidate,
        val changeFraction: Double? = null,
        val changeKr: Double? = null,
        val partial: Boolean = false,
    ) {
        val isEvaluated: Boolean get() = changeFraction != null
    }

    /**
     * Utvecklingen för en kandidat. [latestNav] är senast kända kurs — ur kurscachen (fylld
     * budgeterat av bakgrundskörningen) eller ur den historik skärmen just hämtat.
     *
     * En nollpunkt på 0 eller mindre ger ingen utveckling: det är inte en kurs, och en division
     * med den hade gett ett oändligt "resultat" som såg ut som ett fynd.
     */
    fun outcome(
        candidate: SwitchWatchCandidate,
        latestNav: Double?,
        proceedsKr: Double?,
        soldAtEpochDay: Long,
    ): CandidateOutcome {
        val start = candidate.navAtStart
        if (start == null || start <= 0.0 || latestNav == null) return CandidateOutcome(candidate)

        val fraction = latestNav / start - 1.0
        return CandidateOutcome(
            candidate = candidate,
            changeFraction = fraction,
            changeKr = proceedsKr?.let { it * fraction },
            partial = candidate.navAtStartEpochDay != null && candidate.navAtStartEpochDay > soldAtEpochDay,
        )
    }

    /**
     * Kandidaterna rangordnade — bäst utveckling först, ej utvärderade sist. Ordningen är ett
     * *svar*, inte en sortering av bekvämlighet: skärmen finns för att peka ut vilket alternativ
     * som gått bäst sedan säljet. En ej utvärderad rad hamnar sist men **försvinner aldrig**,
     * annars hade en kandidat vars kurs inte gick att hämta sett ut som om den aldrig lagts till.
     */
    fun ranked(outcomes: List<CandidateOutcome>): List<CandidateOutcome> =
        outcomes.sortedWith(
            compareByDescending<CandidateOutcome> { it.isEvaluated }
                .thenByDescending { it.changeFraction ?: Double.NEGATIVE_INFINITY }
                .thenBy { it.candidate.position },
        )

    /** Antal dygn sedan säljdagen, minst 0 — "dag N" i väntan på likviden. */
    fun daysWaiting(watch: SwitchWatch, today: LocalDate): Long =
        (today.toEpochDay() - watch.soldAtEpochDay).coerceAtLeast(0)

    /**
     * Sant när en **öppen** bevakning passerat [WATCH_TTL_DAYS]. En redan stängd bevakning är
     * aldrig utgången — den har ett avslut, och att räkna om den till "utgången" hade skrivit
     * över vad användaren faktiskt gjorde.
     */
    fun isExpired(watch: SwitchWatch, today: LocalDate): Boolean =
        watch.isOpen && daysWaiting(watch, today) > WATCH_TTL_DAYS

    /**
     * Nollpunkten ur en hämtad kurshistorik: kursen på [soldAtEpochDay], annars **första kända
     * dagen efter**. Aldrig en dag före säljet — en kurs från dagen innan hade mätt en
     * utveckling som delvis skedde medan pengarna fortfarande låg i den sålda fonden.
     *
     * @param history (epochDay, NAV) i valfri ordning.
     * @return dagen och kursen, eller null när historiken inte når fram — kandidaten visas då
     *   som ej utvärderad tills en senare hämtning lyckas.
     */
    fun anchor(history: List<Pair<Long, Double>>, soldAtEpochDay: Long): Pair<Long, Double>? =
        history.filter { it.first >= soldAtEpochDay }.minByOrNull { it.first }
}
