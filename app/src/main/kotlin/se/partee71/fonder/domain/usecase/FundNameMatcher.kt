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
 *
 * Exportraden anger även fondbolagets namn ([importedCompanyName], t.ex.
 * "Handelsbanken Fonder AB"). Både export- och katalogfondnamn **inleds** normalt med
 * fondbolagets varumärke ("Handelsbanken Sverige …", "AMF Aktiefond …"), så en kandidat
 * vars namn börjar med bolagets kärnnamn (via [FundCompanyMatcher.coreBrandName], t.ex.
 * "Handelsbanken Fonder AB" → "Handelsbanken") ges ett litet försprång. Det avgör mellan
 * annars likvärdiga fonder från olika bolag utan att utesluta andra kandidater. (Tidigare
 * mellansteg — matcha bolagsnamnet mot katalogens separata fondbolagslista — var för
 * skört: "Handelsbanken Fonder AB" nådde inte likhetströskeln mot katalogbolaget
 * "Handelsbanken".)
 */
object FundNameMatcher {

    /** Under denna likhet anses ingen kandidat vara en tillförlitlig automatisk träff. */
    private const val CONFIDENCE_THRESHOLD = 0.5

    /** Litet försprång för en kandidat vars namn inleds med importradens fondbolag. */
    private const val COMPANY_MATCH_BONUS = 0.2

    data class Match(val fund: Fund, val confidence: Double)

    /**
     * Bästa kandidat bland [candidates] för [importedFundName], eller null om ingen är
     * tillräckligt lik. [importedCompanyName] är valfritt och används bara för att ge
     * kandidater vars namn inleds med samma fondbolag ett litet försprång vid annars jämna
     * träffar.
     *
     * Ett tunt skal ovanpå [rankedMatches] — se den för varför en enskild "bästa" kandidat
     * inte alltid är rätt kandidat att ISIN-verifiera.
     */
    fun bestMatch(
        importedFundName: String,
        candidates: List<Fund>,
        importedCompanyName: String? = null,
    ): Match? = rankedMatches(importedFundName, candidates, importedCompanyName).firstOrNull()

    /**
     * Alla kandidater i [candidates] som är tillräckligt lika [importedFundName], fallande
     * sorterade efter likhet (bästa först). Tom lista om ingen når tröskeln.
     *
     * En andelsklassfamilj (t.ex. Handelsbankens "Sverige"-fonder) innehåller ofta en
     * suffixlös basfond ("Handelsbanken Sverige") vid sidan av flera suffixerade varianter
     * ("(A10 SEK)", "(A9 SEK)", "(B1 SEK)"). Delar målnamnet ett suffix med fel varianter
     * ("Handelsbanken Sverige (A1 SEK)" mot "(A10 SEK)") kan Jaccard-likheten råka rangordna
     * en felaktig syskonfond högre än den suffixlösa basfonden som faktiskt är rätt träff —
     * även om båda ligger över konfidenströskeln. Anropare som kan verifiera en kandidat
     * ytterligare (t.ex. mot ISIN) ska därför pröva flera rankade kandidater i tur och
     * ordning, inte bara den högst rankade.
     */
    fun rankedMatches(
        importedFundName: String,
        candidates: List<Fund>,
        importedCompanyName: String? = null,
    ): List<Match> {
        val targetTokens = tokenize(importedFundName)
        if (targetTokens.isEmpty()) return emptyList()

        val companyBrand = importedCompanyName
            ?.let { FundCompanyMatcher.coreBrandName(it) }
            ?.takeIf { it.isNotBlank() }

        return candidates
            .map { fund ->
                var score = similarity(targetTokens, tokenize(fund.name))
                if (companyBrand != null && fund.name.startsWith(companyBrand, ignoreCase = true)) {
                    score += COMPANY_MATCH_BONUS
                }
                fund to score
            }
            .filter { it.second >= CONFIDENCE_THRESHOLD }
            .sortedByDescending { it.second }
            .map { (fund, score) -> Match(fund, score.coerceIn(0.0, 1.0)) }
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
