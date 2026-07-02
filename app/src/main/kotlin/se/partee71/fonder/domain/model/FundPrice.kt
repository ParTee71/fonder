package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/** En daglig fondkurs (NAV), hämtad från handelsbanken.fondlista.se (se issue #2/#3). */
@Serializable
data class FundPrice(
    val fundId: String,
    val epochDay: Long,
    val nav: Double,
    val currency: String = "SEK",
)
