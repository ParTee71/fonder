package se.partee71.fonder.data.room.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import se.partee71.fonder.domain.model.FundMetadata
import se.partee71.fonder.domain.model.FundTag

/**
 * Cachad fondmetadata från Avanzas fond-API (KRAVLISTA TP-21) — härledd data, precis som
 * `fx_rates` (TP-20): går den förlorad hämtas den bara om vid nästa fråga. Ingår därför
 * medvetet **inte** i backup-kontraktet (NFR-1).
 *
 * [tagsJson] lagrar [FundMetadata.tags] som JSON-text i en enda kolumn — krävs för att en
 * cachad rad ska kunna filtreras på region/bransch/fondtyp offline med samma semantik som
 * källan (se [se.partee71.fonder.domain.usecase.FundScreenFilter]). Kodningen sköts av
 * [FundTagsCodec], utanför den här klassen — Rooms KSP-processor läser annars av *alla*
 * deklarationer i en `@Entity`-klass (inklusive companion-objektet) för att bygga sin
 * kolumnmodell, och `FundTag.serializer()` (syntetiserad av kotlinx.serialization-pluginet)
 * var inte synlig för den processorn när den låg i entitetens eget companion-objekt —
 * `[MissingType]` i KSP-loggen trots att en vanlig Kotlin-kompilering var felfri.
 */
@Entity(tableName = "fund_metadata")
data class FundMetadataEntity(
    @PrimaryKey val isin: String,
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
    val tagsJson: String,
    val availableAtHandelsbanken: Boolean?,
    val availabilityResolvedAtEpochDay: Long?,
    val fetchedAtEpochDay: Long,
    /** Billigaste verifierat köpbara alternativets ISIN (ANA-9, HEM-6, issue #61) — se [FundMetadata.cheapestAlternativeIsin]. */
    val cheapestAlternativeIsin: String? = null,
    val cheapestAlternativeFee: Double? = null,
    /** Null = aldrig jämfört. Satt (oavsett [cheapestAlternativeIsin]) = jämfört den dagen. */
    val comparisonResolvedAtEpochDay: Long? = null,
    /** Källans 12-månadersavkastning (issue #70) — se [FundMetadata.developmentOneYear]. */
    val developmentOneYear: Double? = null,
) {
    fun toDomain() = FundMetadata(
        isin = isin,
        name = name,
        orderbookId = orderbookId,
        totalFee = totalFee,
        managementFee = managementFee,
        category = category,
        fundType = fundType,
        companyName = companyName,
        risk = risk,
        indexFund = indexFund,
        startDateEpochDay = startDateEpochDay,
        minimumBuy = minimumBuy,
        tags = FundTagsCodec.decode(tagsJson),
        availableAtHandelsbanken = availableAtHandelsbanken,
        cheapestAlternativeIsin = cheapestAlternativeIsin,
        cheapestAlternativeFee = cheapestAlternativeFee,
        comparisonResolvedAtEpochDay = comparisonResolvedAtEpochDay,
        developmentOneYear = developmentOneYear,
    )

    companion object {
        fun fromDomain(
            metadata: FundMetadata,
            fetchedAtEpochDay: Long,
            availabilityResolvedAtEpochDay: Long? = null,
        ) = FundMetadataEntity(
            isin = metadata.isin,
            name = metadata.name,
            orderbookId = metadata.orderbookId,
            totalFee = metadata.totalFee,
            managementFee = metadata.managementFee,
            category = metadata.category,
            fundType = metadata.fundType,
            companyName = metadata.companyName,
            risk = metadata.risk,
            indexFund = metadata.indexFund,
            startDateEpochDay = metadata.startDateEpochDay,
            minimumBuy = metadata.minimumBuy,
            tagsJson = FundTagsCodec.encode(metadata.tags),
            availableAtHandelsbanken = metadata.availableAtHandelsbanken,
            availabilityResolvedAtEpochDay = availabilityResolvedAtEpochDay,
            fetchedAtEpochDay = fetchedAtEpochDay,
            cheapestAlternativeIsin = metadata.cheapestAlternativeIsin,
            cheapestAlternativeFee = metadata.cheapestAlternativeFee,
            comparisonResolvedAtEpochDay = metadata.comparisonResolvedAtEpochDay,
            developmentOneYear = metadata.developmentOneYear,
        )
    }
}

/**
 * JSON-(av)kodning av [FundTag]-listan i [FundMetadataEntity.tagsJson] — medvetet **utanför**
 * `FundMetadataEntity` (se dess KDoc för varför: Rooms KSP-processor kunde annars inte
 * resolva `@Entity`-klassen).
 */
private object FundTagsCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val tagListSerializer = ListSerializer(FundTag.serializer())

    fun encode(tags: List<FundTag>): String = json.encodeToString(tagListSerializer, tags)

    fun decode(tagsJson: String): List<FundTag> =
        runCatching { json.decodeFromString(tagListSerializer, tagsJson) }.getOrElse { emptyList() }
}
