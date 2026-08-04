package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import java.time.LocalDate

/**
 * Gör den senast inspelade bytesplanen ([SuggestionRecord], HEM-8, issue #70) visningsbar —
 * räknar **inte** om planen live, se [Suggestion]. Rent domänlager utan Compose-/ViewModel-
 * beroende så både Hems riskkort (HEM-8) och Fonddetaljs bytesavsnitt (ANA-10, issue #85) läser
 * samma plan med samma regler; det som visas på fondkortet måste vara identiskt med det som
 * visas på Hem, annars ger appen två olika råd om samma byte.
 */
object SwitchPlanResolver {

    /**
     * Ett enskilt föreslaget byte, redo för visning — härlett ur redan inspelade
     * [SuggestionRecord], inte omräknat live: samma nätverkskostnadsskäl som gör att
     * facit-inspelningen själv ligger på bakgrundsworkerns backstop, inte på varje skärmöppning.
     */
    data class Suggestion(
        /** Raden i facit-inspelningen ([SuggestionRecord.id]) — nyckeln "Genomförd" skriver mot (SET-5, issue #80). */
        val recordId: Long,
        /**
         * Platsen i den rangordnade planen (0 = först). Bärs hela vägen till UI:t i stället för att
         * härledas ur listpositionen: planen är girig och sekventiell, så ett byte måste visas med
         * sin **egen** rangordning — annars kunde byte 1 presenteras som "1." när byte 0 fallit bort,
         * och att följa det ensamt flyttar portföljen bort från målet (issue #75).
         */
        val planIndex: Int,
        /** Fonden som säljs — bärs med så en enskild fonds kort kan hitta sina egna byten ([forFund]). */
        val sellIsin: String,
        /** Fonden som köps — se [sellIsin]. */
        val buyIsin: String,
        val sellFundName: String,
        val buyFundName: String,
        val fromLevel: Int,
        val toLevel: Int,
        val feeDeltaPercent: Double,
        /**
         * Beloppet förslaget avser — bytet storleksbestäms till gapet, inte till hela positionen
         * (issue #75), så utan beloppet vore raden tvetydig. Null bara för rader inspelade före
         * dess; raden visas då utan beloppstext i stället för med ett gissat belopp.
         */
        val switchValueKr: Double? = null,
        /** Sant om användaren markerat bytet som genomfört (SET-5, issue #80) — null i inspelningen betyder "inte markerat", inte "inte genomfört". */
        val followed: Boolean = false,
    )

    /**
     * Formaterar [latestBatch] för visning. Tom lista utan ISK/KF ([AccountType.ISK_KF], SET-4)
     * eller innan bakgrundsworkerns backstop hunnit spela in något än — appen väntar hellre än
     * att gissa.
     *
     * Planen är **girig och sekventiell**: byte n är framräknat under antagandet att byte 0…n−1
     * genomförts. Kan en rad inte slås upp (metadata saknas) visas därför inte resten som om den
     * vore fristående — listan kapas vid första luckan, och saknas `planIndex 0` visas ingen plan
     * alls. Annars kunde byte 1 presenteras som "1." och att följa det ensamt flyttade portföljen
     * **bort** från målet (issue #75).
     *
     * Färskhetsgränsen ([SwitchPlanCalc.PLAN_TTL_DAYS]) följer samma princip som HEM-6:s
     * jämförelse: ett gammalt råd är fel på ett sätt gammal avgiftsdata inte är. Slutar
     * backstopen köra ska planen försvinna, inte ligga kvar prissatt mot en portfölj som sedan
     * dess rört sig (issue #75, punkt 5).
     */
    fun resolve(
        accountType: AccountType?,
        latestBatch: List<SuggestionRecord>,
        metadataByIsin: Map<String, FundMetadata>,
        today: LocalDate,
    ): List<Suggestion> {
        if (accountType != AccountType.ISK_KF) return emptyList()

        // Bara planens egna rader (issue #91). DAO-frågan filtrerar redan på typen, men
        // prefix-regeln nedan är det som gör en plan säker att *följa* — och den vilar på att
        // varje rad hör till samma girigt framräknade plan. Ett avgiftsbyte som slank in hade
        // presenterats som planens `planIndex 0`, alltså ett råd som aldrig räknats fram.
        val planRecords = latestBatch.filter { it.kind == SuggestionKind.RISK_PLAN }
        val suggestedAt = planRecords.firstOrNull()?.suggestedAtEpochDay ?: return emptyList()
        if (FundMetadataFreshness.isStale(suggestedAt, today, SwitchPlanCalc.PLAN_TTL_DAYS)) return emptyList()

        val resolved = planRecords.mapNotNull { record ->
            val sellMeta = metadataByIsin[record.sellIsin] ?: return@mapNotNull null
            val buyMeta = metadataByIsin[record.buyIsin] ?: return@mapNotNull null
            val sellLevel = sellMeta.risk ?: return@mapNotNull null
            val buyLevel = buyMeta.risk ?: return@mapNotNull null
            val sellFee = sellMeta.totalFee ?: return@mapNotNull null
            val buyFee = buyMeta.totalFee ?: return@mapNotNull null
            Suggestion(
                recordId = record.id,
                planIndex = record.planIndex,
                sellIsin = record.sellIsin,
                buyIsin = record.buyIsin,
                sellFundName = sellMeta.name,
                buyFundName = buyMeta.name,
                fromLevel = sellLevel,
                toLevel = buyLevel,
                feeDeltaPercent = buyFee - sellFee,
                switchValueKr = record.switchValueKr,
                followed = record.followed == true,
            )
        }

        // Behåll bara den obrutna prefixen från och med planIndex 0.
        return resolved.sortedBy { it.planIndex }
            .withIndex()
            .takeWhile { (position, suggestion) -> suggestion.planIndex == position }
            .map { it.value }
    }

    /**
     * De byten i [plan] som rör fonden med [isin] — antingen som säljkandidat ("byt härifrån")
     * eller som köpkandidat ("byt hit"). Rangordningen ur [Suggestion.planIndex] behålls; hela
     * planens prefix-regel har redan tillämpats i [resolve], så filtreringen här kan aldrig
     * "läka" en kapad plan. Tom lista utan ISIN — utan ett känt ISIN går fonden inte att koppla
     * till någon inspelad rad (samma princip som ANA-9:s Unavailable).
     */
    fun forFund(plan: List<Suggestion>, isin: String?): List<Suggestion> {
        if (isin == null) return emptyList()
        return plan.filter { it.sellIsin == isin || it.buyIsin == isin }
    }
}
