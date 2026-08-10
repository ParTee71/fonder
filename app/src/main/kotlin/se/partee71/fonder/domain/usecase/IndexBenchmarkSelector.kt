package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.FundScreenSortDirection
import se.partee71.fonder.domain.model.FundTag

/**
 * Väljer den **referensfond** portföljens avkastning jämförs mot (HEM-10) — en bred global
 * aktieindexfond, som proxy för "index".
 *
 * Appen har ingen indexdata, bara fond-NAV (TP-9/TP-14): det finns ingen serie för OMX eller
 * MSCI World att hämta. En billig, bred indexfond är däremot både hämtbar med befintliga källor
 * **och** det alternativ jämförelsen egentligen handlar om — "hade pengarna gett mer i en
 * indexfond?" är en fråga om en fond man kunnat köpa, inte om ett teoretiskt index utan avgift.
 *
 * Urvalet är avsiktligt **deterministiskt**: samma katalog ger samma fond oavsett i vilken
 * ordning källan råkade leverera den. En referensfond som byts godtyckligt mellan körningar
 * hade ritat om historiken bakom ryggen på användaren, och en jämförelsekurva som ändrar sig
 * utan att något hänt är värre än ingen kurva. Avgörs i tur och ordning av lägst avgift, längst
 * historik och sist ISIN — de två sista bara för att bryta lika, aldrig som egna kvalitetsmått.
 *
 * Ren och testbar, samma mönster som [SwitchPlanCalc]/[FeeComparisonCalc]: frågan ställs och
 * resultatet cachas i datalagret, bedömningen görs här.
 */
object IndexBenchmarkSelector {

    /**
     * Källans egna vokabulärtitlar (TP-21) för en global aktiefond — **transportvärden**, samma
     * kategori som [FundScreenFilter.SORT_FIELD_TOTAL_FEE]: de skickas rakt av i frågan och
     * används sedan om för att kontrollera svaret. Källan ignorerar ett filter den inte känner
     * igen tyst ("fail open", verifierat i TP-21), så [select] filtrerar alltid om lokalt på
     * samma titlar — annars hade en ignorerad dimension kunnat ge en räntefond eller en
     * Sverigefond som "index" utan att något syntes.
     */
    const val TAG_TYPE_EQUITY = "Aktiefond"
    const val TAG_REGION_GLOBAL = "Global"

    /**
     * Frågan som hämtar kandidaterna. Sorterad på avgift stigande eftersom källans sida är hårt
     * låst till 20 träffar (TP-21) — utan sorteringen är det godtyckliga 20 fonder som blir
     * synliga, och den billigaste globala indexfonden kan ligga utanför sidan. Att sorteringen
     * kan ignoreras av källan spelar ingen roll för korrektheten: [select] rangordnar ändå om
     * lokalt.
     */
    val QUERY = FundScreenQuery(
        fundType = listOf(TAG_TYPE_EQUITY),
        region = listOf(TAG_REGION_GLOBAL),
        sortField = FundScreenFilter.SORT_FIELD_TOTAL_FEE,
        sortDirection = FundScreenSortDirection.ASCENDING,
    )

    /**
     * Referensfonden bland [candidates], eller null om ingen duger — då visas portföljkurvan
     * ensam med en förklaring, aldrig en jämförelse mot en fond som inte är ett brett globalt
     * index.
     *
     * Kraven är alla hårda: [FundMetadata.indexFund], global aktiefond enligt källans egna
     * taggar, och en känd avgift (utan den går fonderna inte att rangordna alls, och en
     * referensfond vald på slumpen är inte en referens).
     */
    fun select(candidates: List<FundMetadata>): FundMetadata? =
        candidates
            .filter { it.indexFund && it.totalFee != null && isGlobalEquity(it) }
            .minWithOrNull(
                compareBy<FundMetadata> { it.totalFee }
                    // Längre historik = jämförelsen når längre bak innan den måste ge upp
                    // (se PortfolioReturnSeriesCalc.benchmark). Okänt startdatum sorteras sist
                    // genom att behandlas som "startade idag", aldrig som "startade år noll".
                    .thenBy { it.startDateEpochDay ?: Long.MAX_VALUE }
                    .thenBy { it.isin },
            )

    private fun isGlobalEquity(metadata: FundMetadata): Boolean =
        metadata.tags.any { it.category == FundTag.CATEGORY_TYPE && it.title == TAG_TYPE_EQUITY } &&
            metadata.tags.any { it.category == FundTag.CATEGORY_COMMON_REGION && it.title == TAG_REGION_GLOBAL }
}
