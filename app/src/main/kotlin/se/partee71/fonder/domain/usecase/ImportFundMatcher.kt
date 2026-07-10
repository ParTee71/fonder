package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund

/**
 * Matchar en importerad rad (Excel-innehav eller PDF-avräkningsnota) mot en fond i appen, i
 * prioritetsordning (se KRAVLISTA TP-13/TP-14) — delad mellan
 * `ImportHoldingsViewModel` och `ImportOrdersViewModel` (regel 4, ingen dubblerad
 * matchningslogik):
 * 1. Redan bevakad fond med samma ISIN — undviker dubbletter vid upprepad import eller om
 *    fonden redan bekräftats manuellt i Fonddetalj.
 * 2. Exakt ISIN-träff via [findFundByIsin] (Avanza m.fl.) — täcker fonder som saknas i
 *    Handelsbankens katalog och undviker fel andelsklass som ren namnmatchning kan råka ut för.
 * 3. [FundNameMatcher] mot Handelsbankens katalog på fondnamn, som sista utväg.
 */
object ImportFundMatcher {
    suspend fun match(
        isin: String,
        fundName: String,
        fundCompanyName: String?,
        catalogFunds: List<Fund>,
        trackedFunds: List<Fund>,
        findFundByIsin: suspend (String) -> Fund?,
    ): FundNameMatcher.Match? {
        trackedFunds.firstOrNull { it.isin == isin }?.let { return FundNameMatcher.Match(it, 1.0) }
        findFundByIsin(isin)?.let { return FundNameMatcher.Match(it, 1.0) }
        return FundNameMatcher.bestMatch(fundName, catalogFunds, fundCompanyName)
    }
}
