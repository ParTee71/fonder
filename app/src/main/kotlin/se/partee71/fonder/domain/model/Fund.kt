package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/**
 * En fond i användarens bevakning/portfölj.
 *
 * Identitet: [fundId] är Handelsbankens fondlista-plattforms egen kod (t.ex. "SHB0000627"),
 * inte ISIN — källan (spike-issue #2) exponerar inget ISIN.
 */
@Serializable
data class Fund(
    val fundId: String,
    val name: String,
    val currency: String = "SEK",
)
