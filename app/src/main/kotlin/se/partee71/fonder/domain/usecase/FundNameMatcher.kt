package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund

/**
 * Föreslår vilken katalogfond en importerad innehavsrad (issue #8) motsvarar, genom att
 * jämföra fondnamn ur en extern källa (Excel-export, som identifierar fonder med ISIN — se
 * [se.partee71.fonder.domain.model.ImportedHoldingRow]) mot fondlista-katalogens namn.
 *
 * Namnen skiljer sig ofta i detaljer — t.ex. upprepar exportens namn ibland fondbolaget
 * ("Franklin Templeton Franklin Gold and Precious Metals Fund"). Ordbaserad likhet
 * (Jaccard över signifikanta ord) tål sådana skillnader bättre än ett rakt teckenavstånd,
 * eftersom extra/saknade ord bara späder ut träffen i stället för att slå ut den helt.
 */
object FundNameMatcher {

    /** Under denna likhet anses ingen kandidat vara en tillförlitlig automatisk träff. */
    private const val CONFIDENCE_THRESHOLD = 0.5

    data class Match(val fund: Fund, val confidence: Double)

    /** Bästa kandidat bland [candidates] för [importedFundName], eller null om ingen är tillräckligt lik. */
    fun bestMatch(importedFundName: String, candidates: List<Fund>): Match? {
        val targetTokens = tokenize(importedFundName)
        if (targetTokens.isEmpty()) return null

        val best = candidates
            .map { fund -> fund to similarity(targetTokens, tokenize(fund.name)) }
            .maxByOrNull { it.second }
            ?: return null

        return if (best.second >= CONFIDENCE_THRESHOLD) Match(best.first, best.second) else null
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
