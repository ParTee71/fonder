package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/**
 * En fond i användarens bevakning/portfölj.
 *
 * Identiteten (ISIN vs Handelsbankens interna id) avgörs i spike-issue #2. Tills vidare
 * bär [isin] fondens stabila nyckel; byt fält när beslutet är taget.
 */
@Serializable
data class Fund(
    val isin: String,
    val name: String,
    val currency: String = "SEK",
)
