package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/**
 * Ett fondbolag i handelsbanken.fondlista.se:s delade fondlista (samma plattform används
 * av flera banker, med tusentals fonder från olika bolag i samma sökbara katalog).
 */
@Serializable
data class FundCompany(
    val id: String,
    val name: String,
) {
    companion object {
        const val HANDELSBANKEN_ID = "1"
    }
}
