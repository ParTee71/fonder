package se.partee71.fonder.domain.model

/**
 * Fondmetadata från Avanzas odokumenterade fond-API (`fund-guide/list`, se KRAVLISTA TP-21) —
 * avgift, kategori, fondtyp och risk för fonder som appen i övrigt bara känner via
 * Handelsbankens fondlista-plattform (namn, [Fund.fundId], NAV).
 *
 * Källans egna avkastnings-/riskmått (`sharpeRatio`, `standardDeviation`,
 * `developmentOneYear` m.fl.) tas **inte** med här — appen har redan en egen sanning för
 * sådana mått, härledd ur NAV-historiken (`FundAnalysisCalc`, ANA-1/ANA-7), och att cacha
 * källans siffror bredvid hade gett två olika svar på samma fråga.
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
)
