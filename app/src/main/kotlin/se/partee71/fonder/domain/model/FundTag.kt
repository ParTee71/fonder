package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/**
 * En dimension-tagg på en fond ur Avanzas fond-API (`tagList`, se KRAVLISTA TP-21) — t.ex.
 * `{title="Sverige", category="COMMON_REGION"}`. [category] är källans egen
 * `fundTagCategory` (verifierat: `TYPE`, `COMMON_REGION`, `OTHER_REGION`, `INDUSTRY`,
 * `ALIGNMENT`, `MISC`, `INDEX`, `INTEREST`) — bevarad rakt av, inte omtolkad, så en ny
 * kategori källan lägger till inte tappas bort vid parsning.
 *
 * Måste lagras tillsammans med [FundMetadata]: utan taggarna kan en cachad rad inte
 * filtreras på region/bransch/fondtyp offline (se [se.partee71.fonder.domain.usecase.FundScreenFilter]).
 */
@Serializable
data class FundTag(
    val title: String,
    val category: String,
) {
    companion object {
        /** Källans `fundTagCategory`-värden som [se.partee71.fonder.domain.usecase.FundScreenFilter] vet att matcha mot ett filter. */
        const val CATEGORY_TYPE = "TYPE"
        const val CATEGORY_COMMON_REGION = "COMMON_REGION"
        const val CATEGORY_INDUSTRY = "INDUSTRY"
    }
}
