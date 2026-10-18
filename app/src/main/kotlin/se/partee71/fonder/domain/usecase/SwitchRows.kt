package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCloseReason

/** Ett bytes tillstånd på Beslut (BYT-4) — det chipet på raden säger. */
enum class SwitchRowState {
    /** Ett byte i bytesplanen som väntar på beslut (HEM-8). */
    PROPOSED,

    /** Ett billigare alternativ som väntar på beslut (ANA-9). */
    FEE,

    /** Sålt, väntar på köp — en öppen bevakning som inte passerat [SwitchWatchCalc.WATCH_TTL_DAYS] (BYT-1). */
    SOLD,

    /** Bevakningen stängd som köpt. */
    BOUGHT,

    /** Bevakningen avbruten av användaren. */
    CANCELLED,

    /** Bevakningen stängd som utgången — eller öppen men äldre än [SwitchWatchCalc.WATCH_TTL_DAYS]. */
    EXPIRED,

    /** Förslaget avstått (HEM-14). */
    DISMISSED
}

/** Varifrån ett sålt byte kom — styr radens ursprungsetikett ("Bytesplan · byte 1", "Billigare alternativ"). */
enum class SwitchOrigin { PLAN, FEE, STANDALONE }

/** En rad i Besluts lista "Byten" (BYT-4) — **ett** byte, en rad, med sitt tillstånd. */
sealed interface SwitchRow {
    /** Stabil nyckel för listan. */
    val key: String
    val state: SwitchRowState

    /**
     * Ett förslag som väntar på beslut — [SwitchRowState.PROPOSED] (planen) eller [SwitchRowState.FEE]
     * (billigare alternativ, [cheaper] satt). [watched] = säljfonden har redan en öppen bevakning, så
     * ett förslag kvitterat i Facit inte erbjuder "Bevaka alternativ" igen.
     */
    data class Suggested(
        val suggestion: SwitchPlanResolver.Suggestion,
        val cheaper: CheaperAlternative? = null,
        val watched: Boolean = false
    ) : SwitchRow {
        override val key: String get() = "suggestion-${suggestion.recordId}"
        override val state: SwitchRowState get() = if (cheaper != null) SwitchRowState.FEE else SwitchRowState.PROPOSED

        /** Väntar på användaren: ett förslag kvitterat i Facit (SET-5) räknas inte. */
        val waiting: Boolean get() = !suggestion.followed
    }

    /** Sålt, väntar på köp — den öppna bevakningen. [planIndex] när källförslaget är en känd planrad. */
    data class Sold(
        val watch: OpenSwitchWatch,
        val origin: SwitchOrigin,
        val planIndex: Int? = null
    ) : SwitchRow {
        override val key: String get() = "watch-${watch.watchId}"
        override val state: SwitchRowState get() = SwitchRowState.SOLD
    }

    /** Ett avslutat byte — [SwitchRowState.BOUGHT], [SwitchRowState.CANCELLED] eller [SwitchRowState.EXPIRED]. */
    data class Closed(
        val watchId: Long,
        override val state: SwitchRowState,
        val sellFundName: String,
        /** Den köpta fondens namn ur bevakningens kandidater; null när köpet inte gjordes eller fonden inte bevakades. */
        val boughtFundName: String? = null,
        val closedAtEpochDay: Long? = null
    ) : SwitchRow {
        override val key: String get() = "closed-$watchId"
    }

    /** Ett avstått förslag (HEM-14) som kan ångras. */
    data class Dismissed(val suggestion: SwitchPlanResolver.Suggestion) : SwitchRow {
        override val key: String get() = "dismissed-${suggestion.kind}-${suggestion.recordId}"
        override val state: SwitchRowState get() = SwitchRowState.DISMISSED
    }
}

/**
 * Listan "Byten" på Beslut (BYT-4, NAV-9): [active] — det som väntar — och [closed] — den hopfällda
 * sektionen "Avslutade och avstådda". Antalen härifrån är de enda: Besluts rubrik, Översiktens
 * "Att besluta" (HEM-13) och flikmärket läser samma värden, så de kan aldrig skilja sig.
 */
data class SwitchRows(
    val active: List<SwitchRow> = emptyList(),
    val closed: List<SwitchRow> = emptyList()
) {
    /** Planens förslag som väntar på beslut. */
    val proposedCount: Int get() = active.count { it is SwitchRow.Suggested && it.state == SwitchRowState.PROPOSED && it.waiting }

    /** Billigare alternativ som väntar på beslut. */
    val feeCount: Int get() = active.count { it is SwitchRow.Suggested && it.state == SwitchRowState.FEE && it.waiting }

    /** Sålda byten som väntar på köp. */
    val soldCount: Int get() = active.count { it is SwitchRow.Sold }

    /** "N väntar på dig" — Föreslaget + Avgift + Sålt; ett byte räknas en gång. */
    val waitingCount: Int get() = proposedCount + feeCount + soldCount

    val isEmpty: Boolean get() = active.isEmpty()

    companion object {

        /**
         * Bygger listan ur [decisions] och de stängda bevakningarna.
         *
         * - En öppen bevakning blir **en** Sålt-rad. Är dess källförslag en visad planrad eller ett
         *   billigare alternativ (samma inspelade rad eller samma fondpar) ersätter den förslagsraden —
         *   ett byte, en rad.
         * - Planrader och billigare alternativ som är pågående ([SwitchPlanResolver.Suggestion.inProgress])
         *   visas inte som förslag; deras bevakning står redan som Sålt.
         * - Ordning: Sålt (repositoriets ordning), planens förslag i `planIndex`-ordning, billigare alternativ.
         * - [closedWatches]: stängda bevakningar visas i [SwitchWatchCalc.WATCH_TTL_DAYS] dagar efter
         *   stängningen, nyast först; en **öppen** bevakning i listan (passerat gränsen men ännu inte
         *   stängd av bakgrundskörningen) visas som Utgånget. Därefter de avstådda förslagen, planens först.
         */
        fun build(decisions: PendingDecisions, closedWatches: List<SwitchWatch>, today: LocalDate): SwitchRows {
            val plan = decisions.switchPlan
            val fees = decisions.cheaperAlternatives

            // Öppna och stängda bevakningar kommer ur två flöden: under en stängning kan samma
            // bevakning synas i båda ett ögonblick. Avslutet vinner — ett byte, en rad.
            val closedIds = closedWatches.filterNot { it.isOpen }.mapTo(mutableSetOf()) { it.id }
            val openWatches = decisions.openWatches.filterNot { it.watchId in closedIds }
            val sold = openWatches.map { watch ->
                val planRow = plan.firstOrNull { watch.isSourceOf(it) }
                val feeRow = if (planRow == null) fees.firstOrNull { watch.isSourceOf(it.suggestion) } else null
                when {
                    planRow != null -> SwitchRow.Sold(watch, SwitchOrigin.PLAN, planRow.planIndex)

                    feeRow != null -> SwitchRow.Sold(watch, SwitchOrigin.FEE)

                    watch.sourceKind == SuggestionKind.FEE -> SwitchRow.Sold(watch, SwitchOrigin.FEE)

                    // Källraden kan vara gallrad (sourceKind null) — ett känt källförslag är ändå inget eget sälj.
                    watch.sourceRecordId != null -> SwitchRow.Sold(watch, SwitchOrigin.PLAN)

                    else -> SwitchRow.Sold(watch, SwitchOrigin.STANDALONE)
                }
            }
            val consumed: (SwitchPlanResolver.Suggestion) -> Boolean = { row ->
                // En pågående-markering som bara kommer av en nyss stängd bevakning gäller inte längre —
                // annars försvann både Sålt-raden och förslaget ett ögonblick.
                openWatches.any { it.isSourceOf(row) } ||
                    (row.inProgress && decisions.openWatches.none { it.watchId in closedIds && it.isSourceOf(row) })
            }
            val proposed = plan
                .filterNot(consumed)
                .sortedBy { it.planIndex }
                .map { SwitchRow.Suggested(it, watched = it.sellIsin in decisions.watchedSellIsins) }
            val cheaper = fees
                .filterNot { consumed(it.suggestion) }
                .map { SwitchRow.Suggested(it.suggestion, cheaper = it, watched = it.suggestion.sellIsin in decisions.watchedSellIsins) }

            val since = today.toEpochDay() - SwitchWatchCalc.WATCH_TTL_DAYS
            val closed = closedWatches
                .filter { it.isOpen || (it.closedAtEpochDay ?: Long.MIN_VALUE) >= since }
                .sortedBy { it.isOpen } // stängd före öppen, så distinctBy behåller avslutet
                .distinctBy { it.id }
                .sortedByDescending { it.closedAtEpochDay ?: today.toEpochDay() }
                .map { it.toClosedRow() }
            val dismissed = (decisions.dismissedSuggestions + decisions.dismissedCheaperAlternatives.map { it.suggestion })
                // Ett avstått par som ändå sålts står redan som Sålt — inte en gång till som Avstått.
                .filterNot { row -> openWatches.any { it.isSourceOf(row) } }
                .map { SwitchRow.Dismissed(it) }

            return SwitchRows(active = sold + proposed + cheaper, closed = closed + dismissed)
        }

        private fun OpenSwitchWatch.isSourceOf(row: SwitchPlanResolver.Suggestion): Boolean = (sourceRecordId != null && sourceRecordId == row.recordId) || (sourcePair != null && sourcePair == row.sellIsin to row.buyIsin)

        private fun SwitchWatch.toClosedRow(): SwitchRow.Closed {
            val state = when {
                isOpen -> SwitchRowState.EXPIRED
                closeReason == SwitchWatchCloseReason.KOPT -> SwitchRowState.BOUGHT
                closeReason == SwitchWatchCloseReason.AVBRUTEN -> SwitchRowState.CANCELLED
                else -> SwitchRowState.EXPIRED
            }
            return SwitchRow.Closed(
                watchId = id,
                state = state,
                sellFundName = sellFundName,
                boughtFundName = if (state == SwitchRowState.BOUGHT) candidates.firstOrNull { it.isin == boughtIsin }?.name else null,
                closedAtEpochDay = closedAtEpochDay
            )
        }
    }
}
