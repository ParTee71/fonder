package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/**
 * En daglig fondkurs (NAV). Källa och exakt fältuppsättning fastställs i spike-issue #2.
 */
@Serializable
data class FundPrice(
    val fundIsin: String,
    val epochDay: Long,
    val nav: Double,
    val currency: String = "SEK",
)
