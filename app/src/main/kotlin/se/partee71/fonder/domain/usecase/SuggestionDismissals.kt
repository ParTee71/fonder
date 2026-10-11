package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.SuggestionDismissal
import se.partee71.fonder.domain.model.SuggestionKind
import java.time.LocalDate

/**
 * Regeln för avstådda bytesförslag (HEM-14) — ren logik, så Beslut, Översiktens "Att besluta" och
 * flikmärket (via [PendingDecisionsSource]) delar exakt samma tolkning.
 *
 * Ett avstående gäller **fondparet och sorten**, inte en inspelad rad: planen spelas in på nytt
 * varje dygn med nya rader, och ett "nej" till Sälj X → Köp Y ska inte komma tillbaka i morgon.
 * Ett omvänt byte (Sälj Y → Köp X) är ett annat par och påverkas inte.
 */
object SuggestionDismissals {

    /**
     * Hur länge ett avstående gäller. Efter det visas förslaget igen om planen fortfarande ger
     * det — ett "nej" är ett ställningstagande vid en viss tidpunkt, och en plan som efter en
     * månad fortfarande säger samma sak är värd att fråga om igen. Samma horisont som
     * avgiftsjämförelsens färskhet ([FundMetadataFreshness.COMPARISON_TTL_DAYS]).
     */
    const val TTL_DAYS = 30L

    /** Sant medan avståendet gäller [today] — dagen då det gjordes och [TTL_DAYS] dagar framåt. */
    fun isActive(dismissal: SuggestionDismissal, today: LocalDate): Boolean =
        !FundMetadataFreshness.isStale(dismissal.dismissedAtEpochDay, today, TTL_DAYS)

    /**
     * Delar [plan] i de förslag som visas och de användaren avstått från. Ett **genomfört**
     * förslag (SET-5) räknas aldrig som avstått — kvitteringen är det starkare beskedet, och
     * facit mäter det som följt. Ordningen och rangordningen ([SwitchPlanResolver.Suggestion.planIndex])
     * bevaras i båda listorna; ett avstått byte numreras inte om, så det syns att ett steg hoppats över.
     */
    fun partition(
        plan: List<SwitchPlanResolver.Suggestion>,
        dismissals: List<SuggestionDismissal>,
        today: LocalDate,
        kind: SuggestionKind = SuggestionKind.RISK_PLAN,
    ): Pair<List<SwitchPlanResolver.Suggestion>, List<SwitchPlanResolver.Suggestion>> {
        val active = dismissals
            .filter { it.kind == kind && isActive(it, today) }
            .mapTo(mutableSetOf()) { it.sellIsin to it.buyIsin }
        if (active.isEmpty()) return plan to emptyList()
        return plan.partition { it.followed || (it.sellIsin to it.buyIsin) !in active }
    }
}
