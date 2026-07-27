package se.partee71.fonder.domain.usecase

/**
 * Städar ett fondbolagsnamn till dess "kärnnamn" — bolagsnamnet utan bolagsform och
 * parentes, t.ex. "Aberdeen Global Services S.A." → "Aberdeen".
 *
 * Objektet hade tidigare även en `matches`-funktion som *gissade* vilka fonder som tillhörde
 * ett fondbolag (`SHB`-prefix för Handelsbanken, namnprefix för övriga). Den byggde på
 * antagandet att källans eget "Fondbolag"-filter inte filtrerade fondlistan — vilket visade
 * sig vara fel: `company`-parametern filtrerar `select#FundId` exakt (KRAVLISTA TP-18,
 * issue #37). Kopplingen fond → fondbolag hämtas därför numera från källan via
 * `FundPriceRepository.fetchFundsForCompany`, och gissningen är borta.
 *
 * Kvar finns [coreBrandName], som fortfarande behövs av [FundNameMatcher]: importfiler bär
 * ett fondbolagsnamn i fritext, och kandidater vars fondnamn inleds med samma varumärke ska
 * få ett litet försprång vid annars jämna namnträffar (KRAVLISTA TP-13).
 */
object FundCompanyMatcher {

    private val trailingParenthetical = Regex("""\s*\([^)]*\)\s*$""")

    private val corporateSuffixes = listOf(
        "kapitalförvaltning", "kapitalforvaltning", "förvaltning", "forvaltning",
        "fund management", "asset management", "investment management",
        "global services", "fonder", "capital", "group",
        "gmbh", "llp", "llc", "plc", "oyj", "aps",
        "s.a.", "sa", "n.v.", "nv", "a/s", "asa", "as", "ab", "ltd", "inc", "corp",
    )

    /**
     * Bolagets "kärnnamn" utan bolagsform/parentes, t.ex. "Aberdeen Global Services S.A." →
     * "Aberdeen". Parentes och bolagsform kan behöva städdas växelvis (t.ex.
     * "AllianceBernstein (Luxembourg) S.A." → strippa "S.A." → parentesen hamnar sist →
     * strippa den), så båda görs i samma loop tills inget mer ändras.
     */
    fun coreBrandName(companyName: String): String {
        var name = companyName.trim()
        var changed: Boolean
        do {
            changed = false
            val withoutParen = name.replace(trailingParenthetical, "").trim()
            if (withoutParen != name) {
                name = withoutParen
                changed = true
            }
            for (suffix in corporateSuffixes) {
                if (name.endsWith(suffix, ignoreCase = true)) {
                    name = name.dropLast(suffix.length).trim().trimEnd(',').trim()
                    changed = true
                }
            }
        } while (changed && name.isNotBlank())
        return name
    }
}
