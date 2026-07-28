package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund

/**
 * Matchar en importerad rad (Excel-innehav eller PDF-avräkningsnota) mot en fond i appen, i
 * prioritetsordning (se KRAVLISTA TP-13/TP-14/TP-18) — delad mellan
 * `ImportHoldingsViewModel` och `ImportOrdersViewModel` (regel 4, ingen dubblerad
 * matchningslogik):
 * 1. Redan bevakad fond med samma ISIN — undviker dubbletter vid upprepad import eller om
 *    fonden redan bekräftats manuellt i Fonddetalj.
 * 2. Namnträff i fondlista-katalogen, **verifierad mot ISIN** via [lookupIsin]: stämmer en
 *    kandidats ISIN med radens är träffen exakt, och fonden får ett riktigt `FundId` i
 *    stället för att identifieras med sitt ISIN (som steg 3 ger). Katalogen täcker sedan
 *    issue #37 hela plattformen, inte bara Handelsbankens egna fonder, så steget träffar
 *    numera även andra fondbolags fonder. Flera rankade kandidater prövas i turordning (se
 *    [FundNameMatcher.rankedMatches]) — en andelsklassfamilj kan göra att fel syskonfond
 *    rankas högst trots att en suffixlös basfond är den rätta träffen — men bara upp till
 *    [MAX_ISIN_VERIFICATIONS], eftersom varje verifiering är en sidhämtning per importrad.
 *    Verifieras ingen tar steg 3/4 vid.
 * 3. Exakt ISIN-träff via [findFundByIsin] (Avanza m.fl.) — täcker fonder som saknas i
 *    fondlista-katalogen och undviker fel andelsklass som ren namnmatchning kan råka ut för.
 * 4. [FundNameMatcher]s namnträff som sista utväg — samma kandidat som i steg 2, men
 *    overifierad och därför med sin ursprungliga (osäkra) konfidens.
 */
object ImportFundMatcher {

    /** Tak på antal ISIN-verifierade namnkandidater i steg 2 — se KDoc:en ovan. */
    private const val MAX_ISIN_VERIFICATIONS = 5

    suspend fun match(
        isin: String,
        fundName: String,
        fundCompanyName: String?,
        catalogFunds: List<Fund>,
        trackedFunds: List<Fund>,
        findFundByIsin: suspend (String) -> Fund?,
        lookupIsin: suspend (String) -> String? = { null },
    ): FundNameMatcher.Match? {
        trackedFunds.firstOrNull { it.isin == isin }?.let { return FundNameMatcher.Match(it, 1.0) }

        val rankedMatches = FundNameMatcher.rankedMatches(fundName, catalogFunds, fundCompanyName)
        val verified = rankedMatches
            .take(MAX_ISIN_VERIFICATIONS)
            .firstOrNull { lookupIsin(it.fund.fundId) == isin }
        if (verified != null) {
            return FundNameMatcher.Match(verified.fund.copy(isin = isin), 1.0)
        }

        findFundByIsin(isin)?.let { return FundNameMatcher.Match(it, 1.0) }
        return rankedMatches.firstOrNull()
    }
}
