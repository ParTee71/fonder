package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCompany

/**
 * Avgör om en fond tillhör ett valt fondbolag.
 *
 * Källan (handelsbanken.fondlista.se) har **ingen** maskinläsbar koppling mellan fond och
 * fondbolag i den skrapade markupen — `FundId`-listan är en global katalog över alla
 * fondbolags fonder utan bolagsattribut, och sidans eget "Fondbolag"-filter visade sig i
 * praktiken inte filtrera fondlistan (verifierat manuellt, se issue #3-uppföljning).
 * Den här matcharen bygger därför en egen, ungefärlig koppling:
 *
 * 1. **Handelsbanken (id "1"):** använd `FundId`-prefixet `SHB` — täcker även varumärken
 *    som säljs under Handelsbankens fondplattform men som inte heter "Handelsbanken" i
 *    fondnamnet, t.ex. **XACT**-fonderna (Handelsbankens ETF-varumärke).
 * 2. **Övriga bolag:** fondnamnet måste **börja med** bolagets "kärnnamn" — bolagsnamnet
 *    med vanliga bolagsformer (AB, AS, S.A., Ltd, Kapitalförvaltning m.fl.) och eventuell
 *    parentes bortstädade. Ungefärligt: fonder vars varumärke skiljer sig påtagligt från
 *    den formella bolagsbeteckningen (som XACT/Handelsbanken) kan missas.
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

    /** True om [fund] bedöms tillhöra [company]. */
    fun matches(fund: Fund, company: FundCompany): Boolean {
        if (company.id == FundCompany.HANDELSBANKEN_ID) {
            return fund.fundId.startsWith("SHB", ignoreCase = true)
        }
        val core = coreBrandName(company.name)
        if (core.isBlank()) return false
        return fund.name.startsWith(core, ignoreCase = true)
    }

    /**
     * Bolagets "kärnnamn" utan bolagsform/parentes, t.ex. "Aberdeen Global Services S.A." →
     * "Aberdeen". Parentes och bolagsform kan behöva städdas växelvis (t.ex.
     * "AllianceBernstein (Luxembourg) S.A." → strippa "S.A." → parentesen hamnar sist →
     * strippa den), så båda görs i samma loop tills inget mer ändras.
     */
    internal fun coreBrandName(companyName: String): String {
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
