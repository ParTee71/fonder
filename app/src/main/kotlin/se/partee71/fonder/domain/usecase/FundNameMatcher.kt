package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCompany

/**
 * Föreslår vilken katalogfond en importerad innehavsrad (issue #8) motsvarar, genom att
 * jämföra fondnamn ur en extern källa (Excel-export, som identifierar fonder med ISIN — se
 * [se.partee71.fonder.domain.model.ImportedHoldingRow]) mot fondlista-katalogens namn.
 *
 * Namnen skiljer sig ofta i detaljer — t.ex. upprepar exportens namn ibland fondbolaget
 * ("Franklin Templeton Franklin Gold and Precious Metals Fund"). Ordbaserad likhet
 * (Jaccard över signifikanta ord) tål sådana skillnader bättre än ett rakt teckenavstånd,
 * eftersom extra/saknade ord bara späder ut träffen i stället för att slå ut den helt.
 *
 * Exportraden anger även fondbolagets namn ([importedCompanyName], t.ex.
 * "Handelsbanken Fonder AB") — ett användbart ledtrådsfält när flera fondbolag har
 * likartat namngivna fonder. Matchas den mot en katalogpost ([companies], via
 * [FundCompanyMatcher]) ges kandidater från samma bolag ett litet försprång, utan att
 * ovillkorligen utesluta andra kandidater (bolagskopplingen är själv ungefärlig).
 */
object FundNameMatcher {

    /** Under denna likhet anses ingen kandidat vara en tillförlitlig automatisk träff. */
    private const val CONFIDENCE_THRESHOLD = 0.5

    /** Litet försprång för en kandidat som tillhör samma (ungefärligt bestämda) fondbolag. */
    private const val COMPANY_MATCH_BONUS = 0.2

    data class Match(val fund: Fund, val confidence: Double)

    /**
     * Bästa kandidat bland [candidates] för [importedFundName], eller null om ingen är
     * tillräckligt lik. [importedCompanyName] + [companies] är valfria och används bara för
     * att ge kandidater från rätt fondbolag ett litet försprång vid annars jämna träffar.
     */
    fun bestMatch(
        importedFundName: String,
        candidates: List<Fund>,
        importedCompanyName: String? = null,
        companies: List<FundCompany> = emptyList(),
    ): Match? {
        val targetTokens = tokenize(importedFundName)
        if (targetTokens.isEmpty()) return null

        val matchedCompany = importedCompanyName?.let { findCompany(it, companies) }

        val best = candidates
            .map { fund ->
                var score = similarity(targetTokens, tokenize(fund.name))
                if (matchedCompany != null && FundCompanyMatcher.matches(fund, matchedCompany)) {
                    score += COMPANY_MATCH_BONUS
                }
                fund to score
            }
            .maxByOrNull { it.second }
            ?: return null

        return if (best.second >= CONFIDENCE_THRESHOLD) {
            Match(best.first, best.second.coerceIn(0.0, 1.0))
        } else {
            null
        }
    }

    private fun findCompany(importedCompanyName: String, companies: List<FundCompany>): FundCompany? {
        val targetTokens = tokenize(importedCompanyName)
        if (targetTokens.isEmpty()) return null
        return companies
            .map { company -> company to similarity(targetTokens, tokenize(company.name)) }
            .maxByOrNull { it.second }
            ?.takeIf { it.second >= CONFIDENCE_THRESHOLD }
            ?.first
    }

    /** Jaccard-likhet (0..1) mellan två ordmängder. */
    internal fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val union = a union b
        if (union.isEmpty()) return 0.0
        return (a intersect b).size.toDouble() / union.size
    }

    internal fun tokenize(name: String): Set<String> =
        name.lowercase()
            .replace(Regex("[^a-zåäö0-9 ]"), " ")
            .split(" ")
            .filter { it.length > 1 }
            .toSet()
}
