package se.partee71.fonder.domain.model

/** Hela fondkatalogen (alla fondbolag + alla fonder) från en enskild hämtning av källan. */
data class FundCatalog(
    val companies: List<FundCompany>,
    val funds: List<Fund>,
)
