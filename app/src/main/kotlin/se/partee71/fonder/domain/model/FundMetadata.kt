package se.partee71.fonder.domain.model

/**
 * Fondmetadata från Avanzas odokumenterade fond-API (`fund-guide/list`, se KRAVLISTA TP-21) —
 * avgift, kategori, fondtyp och risk för fonder som appen i övrigt bara känner via
 * Handelsbankens fondlista-plattform (namn, [Fund.fundId], NAV).
 *
 * Källans egna avkastnings-/riskmått (`sharpeRatio`, `standardDeviation` m.fl.) tas fortsatt
 * **inte** med här för fonder appen redan känner via innehav — appen har en egen sanning för
 * sådana mått, härledd ur NAV-historiken (`FundAnalysisCalc`, ANA-1/ANA-7), och att cacha
 * källans siffror bredvid hade gett två olika svar på samma fråga. [developmentOneYear] är
 * det enda undantaget (issue #70): den behövs för att rangordna **köpkandidater appen aldrig
 * ägt** (bytesplanen, HEM-8) — där finns ingen konkurrerande NAV-baserad sanning att krocka
 * med, eftersom appen aldrig har haft innehavet.
 *
 * @param isin Fondens ISIN — primärnyckel i cachen ([se.partee71.fonder.data.room.entities.FundMetadataEntity]).
 * @param orderbookId Avanzas interna id — gör framtida uppslag mot samma källa till en lokal
 *   cache-läsning i stället för ett nytt sök-anrop (se TP-14:s `findFundByIsin`/`suggestIsin`).
 * @param tags Fondens dimension-taggar (`tagList`) — krävs för att filtrera en cachad rad
 *   offline med samma semantik som källans egen filtrering, se [se.partee71.fonder.domain.usecase.FundScreenFilter].
 * @param availableAtHandelsbanken Null = inte prövat. Sätts bara av
 *   [se.partee71.fonder.data.repository.FundMetadataRepository.resolveHandelsbankenAvailability],
 *   som ISIN-verifierar mot fondlista-katalogen — ren namnmatchning räcker inte
 *   (andelsklassfamiljer, se issue #45).
 * @param cheapestAlternativeIsin ISIN för det billigaste verifierat köpbara alternativet med
 *   identisk exponering (ANA-9), eller null. [comparisonResolvedAtEpochDay] avgör om null
 *   betyder "aldrig jämfört" eller "jämfört, inget billigare hittades" — se den för
 *   distinktionen (HEM-6, issue #61).
 * @param cheapestAlternativeFee [cheapestAlternativeIsin]s `totalFee` vid jämförelsetillfället,
 *   för att räkna kr-besparingen ur innehavets *aktuella* värde vid visning — aldrig ett sparat
 *   kronbelopp, som blir fel så fort NAV rör sig.
 * @param shownAlternativeIsins ISIN för **samtliga** alternativ jämförelsen visade, rangordnade
 *   som i ANA-9-kortet (störst årsbesparing först) — [cheapestAlternativeIsin] är alltid det
 *   första. Sparas för att facit ska kunna spela in varje *givet* råd (SET-5, issue #91): de tre
 *   alternativen är varandras alternativ, och vilket som helst av dem kan vara det användaren
 *   följer. Tom lista = jämfört utan träff, eller en rad sparad före issue #93.
 * @param comparisonResolvedAtEpochDay Null = aldrig jämfört. Satt datum (oavsett om
 *   [cheapestAlternativeIsin] är null eller ej) = jämfört den dagen. Sätts av
 *   [se.partee71.fonder.data.repository.FundMetadataRepository.suggestCheaperAlternatives].
 * @param developmentOneYear Källans egen 12-månadersavkastning (t.ex. 0.083 = 8,3 %), källan
 *   till [se.partee71.fonder.domain.usecase.SwitchPlanCalc]s rangordning av köpkandidater
 *   (HEM-8, issue #70) — se klassens KDoc för varför just det här måttet undantaget från
 *   principen att inte cacha källans avkastningsmått.
 */
data class FundMetadata(
    val isin: String,
    val name: String,
    val orderbookId: String,
    val totalFee: Double?,
    val managementFee: Double?,
    val category: String?,
    val fundType: String?,
    val companyName: String?,
    val risk: Int?,
    val indexFund: Boolean,
    val startDateEpochDay: Long?,
    val minimumBuy: Double?,
    val tags: List<FundTag>,
    val availableAtHandelsbanken: Boolean? = null,
    val cheapestAlternativeIsin: String? = null,
    val cheapestAlternativeFee: Double? = null,
    val shownAlternativeIsins: List<String> = emptyList(),
    val comparisonResolvedAtEpochDay: Long? = null,
    val developmentOneYear: Double? = null,
)
