package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.SuggestionDismissal
import se.partee71.fonder.domain.model.SuggestionKind
import java.time.LocalDate

/**
 * Regeln för avstådda förslag (HEM-14) — ren logik, så Beslut, Översiktens "Att besluta",
 * flikmärket (via [PendingDecisionsSource]) och Fonddetalj (ANA-10) delar exakt samma tolkning.
 *
 * Ett avstående gäller **fondparet och sorten**, inte en inspelad rad: planen och avgiftsbytena
 * spelas in på nytt varje dygn med nya rader, och ett "nej" till Sälj X → Köp Y ska inte komma
 * tillbaka i morgon. Ett omvänt byte (Sälj Y → Köp X) är ett annat par och påverkas inte, och ett
 * avgiftsbyte (ANA-9) och ett riskplansbyte för samma par avstås var för sig.
 */
object SuggestionDismissals {

    /**
     * Hur länge ett avstående gäller. Efter det visas förslaget igen om planen fortfarande ger
     * det — ett "nej" är ett ställningstagande vid en viss tidpunkt, och en plan som efter en
     * månad fortfarande säger samma sak är värd att fråga om igen. Samma horisont som
     * avgiftsjämförelsens färskhet ([FundMetadataFreshness.COMPARISON_TTL_DAYS]).
     */
    const val TTL_DAYS = 30L

    /** Det ett avstående gäller: sorten och fondparet. */
    data class Key(val kind: SuggestionKind, val sellIsin: String, val buyIsin: String)

    /** Sant medan avståendet gäller [today] — dagen då det gjordes och [TTL_DAYS] dagar framåt. */
    fun isActive(dismissal: SuggestionDismissal, today: LocalDate): Boolean =
        !FundMetadataFreshness.isStale(dismissal.dismissedAtEpochDay, today, TTL_DAYS)

    /** Paren som är avstådda [today]. */
    fun activeKeys(dismissals: List<SuggestionDismissal>, today: LocalDate): Set<Key> =
        dismissals.filter { isActive(it, today) }.mapTo(mutableSetOf()) { Key(it.kind, it.sellIsin, it.buyIsin) }

    /**
     * Sant om ett förslag ska döljas som avstått. Ett **genomfört** förslag (SET-5) räknas aldrig
     * som avstått — kvitteringen är det starkare beskedet, och facit mäter det som följts.
     */
    fun hides(activeKeys: Set<Key>, kind: SuggestionKind, sellIsin: String, buyIsin: String, followed: Boolean): Boolean =
        !followed && Key(kind, sellIsin, buyIsin) in activeKeys

    /** [partition] för en lista förslag. */
    fun partition(
        plan: List<SwitchPlanResolver.Suggestion>,
        dismissals: List<SuggestionDismissal>,
        today: LocalDate,
    ): Pair<List<SwitchPlanResolver.Suggestion>, List<SwitchPlanResolver.Suggestion>> =
        partition(plan, dismissals, today) { it }

    /**
     * Delar [items] i de förslag som visas och de användaren avstått från, efter varje förslags
     * egen sort ([SwitchPlanResolver.Suggestion.kind]). Ordningen och rangordningen
     * ([SwitchPlanResolver.Suggestion.planIndex]) bevaras i båda listorna; ett avstått byte numreras
     * inte om, så det syns att ett steg hoppats över.
     */
    fun <T> partition(
        items: List<T>,
        dismissals: List<SuggestionDismissal>,
        today: LocalDate,
        suggestionOf: (T) -> SwitchPlanResolver.Suggestion,
    ): Pair<List<T>, List<T>> {
        val active = activeKeys(dismissals, today)
        if (active.isEmpty()) return items to emptyList()
        return items.partition { item ->
            val suggestion = suggestionOf(item)
            !hides(active, suggestion.kind, suggestion.sellIsin, suggestion.buyIsin, suggestion.followed)
        }
    }
}
