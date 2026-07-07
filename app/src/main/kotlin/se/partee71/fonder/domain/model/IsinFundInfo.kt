package se.partee71.fonder.domain.model

/** Fondnamn + valuta för ett exakt känt ISIN, från en [se.partee71.fonder.data.network.IsinPriceHistorySource]. */
data class IsinFundInfo(
    val name: String,
    val currency: String,
)
