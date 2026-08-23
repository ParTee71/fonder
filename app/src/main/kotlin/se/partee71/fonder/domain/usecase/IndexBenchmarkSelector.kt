package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundScreenQuery
import se.partee71.fonder.domain.model.FundScreenSortDirection
import se.partee71.fonder.domain.model.FundTag

/**
 * Väljer den **referens** portföljens avkastning jämförs mot (HEM-10) — en viktad blandning av
 * billiga indexfonder som speglar portföljens egen aktieandel.
 *
 * Appen har ingen indexdata, bara fond-NAV (TP-9/TP-14): det finns ingen serie för OMX eller
 * MSCI World att hämta. En billig, bred indexfond är däremot både hämtbar med befintliga källor
 * **och** det alternativ jämförelsen egentligen handlar om — "hade pengarna gett mer i en
 * indexfond?" är en fråga om en fond man kunnat köpa, inte om ett teoretiskt index utan avgift.
 *
 * **Blandningen speglar exponeringen** (issue #101). En 100 %-ig aktiereferens mot en portfölj
 * som innehåller räntefonder svarar inte på "valde jag bra fonder?" utan på "hade jag mer aktier
 * än referensen?" — och det svaret är förutsägbart: i en uppgång förlorar man alltid, i en
 * nedgång vinner man alltid, oavsett fondval. Skillnaden mellan kurvorna blir då marknadens
 * riktning, inte användarens beslut.
 *
 * Urvalet inom varje komponent är avsiktligt **deterministiskt**: samma katalog ger samma fond
 * oavsett i vilken ordning källan råkade leverera den. En referensfond som byts godtyckligt
 * mellan körningar hade ritat om historiken bakom ryggen på användaren. Avgörs i tur och ordning
 * av lägst avgift, längst historik och sist ISIN — de två sista bara för att bryta lika, aldrig
 * som egna kvalitetsmått.
 *
 * Ren och testbar, samma mönster som [SwitchPlanCalc]/[FeeComparisonCalc]: frågan ställs och
 * resultatet cachas i datalagret, bedömningen görs här.
 */
object IndexBenchmarkSelector {

    /**
     * Källans egna vokabulärtitlar (TP-21) — **transportvärden**, samma kategori som
     * [FundScreenFilter.SORT_FIELD_TOTAL_FEE]: de skickas rakt av i frågan och används sedan om
     * för att kontrollera svaret. Källan ignorerar ett filter den inte känner igen tyst ("fail
     * open", verifierat i TP-21), så [select] filtrerar alltid om lokalt på samma titlar —
     * annars hade en ignorerad dimension kunnat ge en räntefond eller en Sverigefond som
     * "globalt aktieindex" utan att något syntes.
     */
    const val TAG_TYPE_EQUITY = "Aktiefond"
    const val TAG_TYPE_BOND = "Räntefond"
    const val TAG_REGION_GLOBAL = "Global"

    /**
     * Under den här vikten tas en komponent bort och resten normaliseras om. En referensdel på
     * någon enstaka procent flyttar inte kurvan mätbart, men kostar ett fondval och en full
     * historikbackfill vid varje skanning — och en teckenförklaring med "97 % / 3 %" påstår en
     * precision blandningen inte har (vikterna är ändå dagens, inte historiska).
     */
    const val MIN_COMPONENT_WEIGHT = 0.05

    /** Frågan som hämtar aktiekandidater — se [BOND_QUERY] för sorteringens skäl. */
    val EQUITY_QUERY = FundScreenQuery(
        fundType = listOf(TAG_TYPE_EQUITY),
        region = listOf(TAG_REGION_GLOBAL),
        sortField = FundScreenFilter.SORT_FIELD_TOTAL_FEE,
        sortDirection = FundScreenSortDirection.ASCENDING,
    )

    /**
     * Räntekandidater. **Utan regionfilter**, till skillnad från [EQUITY_QUERY]: räntefonder är
     * nästan alltid knutna till en valuta eller marknad, och ett globalt filter hade tömt
     * träfflistan i stället för att förfina den.
     *
     * Sorterad på avgift stigande eftersom källans sida är hårt låst till 20 träffar (TP-21) —
     * utan sorteringen är det godtyckliga 20 fonder som blir synliga, och den billigaste kan
     * ligga utanför sidan. Att sorteringen kan ignoreras av källan spelar ingen roll för
     * korrektheten: [select] rangordnar ändå om lokalt.
     */
    val BOND_QUERY = FundScreenQuery(
        fundType = listOf(TAG_TYPE_BOND),
        sortField = FundScreenFilter.SORT_FIELD_TOTAL_FEE,
        sortDirection = FundScreenSortDirection.ASCENDING,
    )

    /** En del av referensen: en fond och dess vikt (andel av varje insättning). */
    data class Component(val metadata: FundMetadata, val weight: Double)

    /**
     * Referensen som helhet. [components] summerar alltid till 1,0 och är aldrig tom;
     * [missingBondComponent] är sant när en räntedel efterfrågades men ingen kandidat fanns, så
     * vikten lades på aktiedelen — vyn säger det hellre än att låtsas att blandningen stämmer.
     */
    data class Benchmark(val components: List<Component>, val missingBondComponent: Boolean = false)

    /**
     * Portföljens aktieandel och hur mycket värde som inte gick att klassificera.
     *
     * [unclassifiedFraction] är andelen av portföljen som varken är [TAG_TYPE_EQUITY] eller
     * [TAG_TYPE_BOND] — blandfonder, okänd typ, och innehav utan metadata. De räknas **varken**
     * som aktier eller räntor: en blandfond är typiskt både och, och att gissa 50/50 vore precis
     * den sortens tysta antagande resten av appen vägrar göra (jämför
     * [PortfolioFeeCalc.Result.unknownFeeCount]). Andelen redovisas i stället.
     */
    data class ExposureSplit(val equityShare: Double, val unclassifiedFraction: Double)

    /**
     * Aktieandelen ur portföljens fondtypsfördelning (POR-9). Räknas över **klassificerat**
     * värde, så en portfölj med 50 % aktier, 25 % räntor och 25 % blandfond ger aktieandelen
     * 2/3 — inte 1/2. Blandfonden syns i stället som [ExposureSplit.unclassifiedFraction].
     *
     * Utan något klassificerat värde alls finns ingen grund för en blandning: då blir det en
     * bred aktiereferens, med hela portföljen markerad som oklassificerad. Det är ett medvetet
     * val av det minst dåliga — en jämförelse mot breda aktier är den vanliga förväntan, och
     * texten säger att fördelningen inte kunde läsas.
     */
    fun exposureSplit(byType: PortfolioExposureCalc.Dimension): ExposureSplit {
        val equity = byType.buckets.filter { it.label == TAG_TYPE_EQUITY }.sumOf { it.valueKr }
        val bond = byType.buckets.filter { it.label == TAG_TYPE_BOND }.sumOf { it.valueKr }
        val classified = equity + bond
        val total = byType.buckets.sumOf { it.valueKr } + byType.unknownValueKr
        val unclassified = if (total <= 0.0) 1.0 else ((total - classified) / total).coerceIn(0.0, 1.0)
        return ExposureSplit(
            equityShare = if (classified <= 0.0) 1.0 else equity / classified,
            unclassifiedFraction = unclassified,
        )
    }

    /**
     * Referensblandningen, eller null om ingen kandidat duger — då visas portföljkurvan ensam
     * med en förklaring, aldrig en jämförelse mot något som inte är ett brett index.
     *
     * [equityShare] 1,0 ger en enda aktiekomponent, alltså exakt beteendet före issue #101 —
     * specialfallet faller ut av samma kod, det är inte en egen gren.
     *
     * Kraven per komponent är hårda: [FundMetadata.indexFund], rätt fondtyp enligt källans egna
     * taggar (och global region för aktiedelen), och en känd avgift — utan den går fonderna inte
     * att rangordna alls, och en referens vald på slumpen är ingen referens.
     */
    fun select(
        equityCandidates: List<FundMetadata>,
        bondCandidates: List<FundMetadata>,
        equityShare: Double,
    ): Benchmark? {
        val share = equityShare.coerceIn(0.0, 1.0)
        val equity = bestOf(equityCandidates) { isGlobalEquity(it) }
        val bond = bestOf(bondCandidates) { hasType(it, TAG_TYPE_BOND) }

        val wanted = buildList {
            if (share >= MIN_COMPONENT_WEIGHT && equity != null) add(equity to share)
            if (1.0 - share >= MIN_COMPONENT_WEIGHT && bond != null) add(bond to 1.0 - share)
        }
        // Efterfrågades en räntedel men fanns ingen kandidat? Vikten hamnar på aktiedelen, och
        // vyn får veta att blandningen inte blev den avsedda.
        val missingBond = 1.0 - share >= MIN_COMPONENT_WEIGHT && bond == null

        val fallback = listOfNotNull(equity?.let { it to 1.0 }, bond?.let { it to 1.0 }).take(1)
        val chosen = wanted.ifEmpty { fallback }
        if (chosen.isEmpty()) return null

        val sum = chosen.sumOf { it.second }
        return Benchmark(
            components = chosen.map { (metadata, weight) -> Component(metadata, weight / sum) },
            missingBondComponent = missingBond,
        )
    }

    private fun bestOf(candidates: List<FundMetadata>, matchesType: (FundMetadata) -> Boolean): FundMetadata? =
        candidates
            .filter { it.indexFund && it.totalFee != null && matchesType(it) }
            .minWithOrNull(
                compareBy<FundMetadata> { it.totalFee }
                    // Längre historik = jämförelsen når längre bak innan den måste ge upp
                    // (se PortfolioReturnSeriesCalc.benchmark). Okänt startdatum sorteras sist
                    // genom att behandlas som "startade idag", aldrig som "startade år noll".
                    .thenBy { it.startDateEpochDay ?: Long.MAX_VALUE }
                    .thenBy { it.isin },
            )

    private fun isGlobalEquity(metadata: FundMetadata): Boolean =
        hasType(metadata, TAG_TYPE_EQUITY) &&
            metadata.tags.any { it.category == FundTag.CATEGORY_COMMON_REGION && it.title == TAG_REGION_GLOBAL }

    private fun hasType(metadata: FundMetadata, title: String): Boolean =
        metadata.tags.any { it.category == FundTag.CATEGORY_TYPE && it.title == title }
}
