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
 * källan (se [se.partee71.fonder.domain.usecase.FundScreenFilter]).
 *
 * [availableAtHandelsbanken]/[availabilityResolvedAtEpochDay] är null tills
 * [se.partee71.fonder.data.repository.FundMetadataRepository.resolveHandelsbankenAvailability]
 * har körts för fonden — se den för TTL-principen.
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
        tags = decodeTags(tagsJson),
        availableAtHandelsbanken = availableAtHandelsbanken,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val tagListSerializer = ListSerializer(FundTag.serializer())

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
            tagsJson = json.encodeToString(tagListSerializer, metadata.tags),
            availableAtHandelsbanken = metadata.availableAtHandelsbanken,
            availabilityResolvedAtEpochDay = availabilityResolvedAtEpochDay,
            fetchedAtEpochDay = fetchedAtEpochDay,
        )

        private fun decodeTags(tagsJson: String): List<FundTag> =
            runCatching { json.decodeFromString(tagListSerializer, tagsJson) }.getOrElse { emptyList() }
    }
}
