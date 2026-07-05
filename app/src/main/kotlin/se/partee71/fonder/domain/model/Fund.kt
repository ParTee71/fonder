package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/**
 * En fond i användarens bevakning/portfölj.
 *
 * Identitet: [fundId] är Handelsbankens fondlista-plattforms egen kod (t.ex. "SHB0000627"),
 * inte ISIN — källan (spike-issue #2) exponerar inget ISIN.
 *
 * [isin] är ett separat, valfritt attribut (saknas för fonder tillagda via fondsök tills
 * ett bekräftats — se [se.partee71.fonder.domain.usecase] och `FondDetaljViewModel`) som
 * används för att hämta full kurshistorik sedan köpdatum från ISIN-baserade källor
 * (Avanza m.fl., se KRAVLISTA TP-14), utöver Handelsbankens FundId-baserade källa.
 */
@Serializable
data class Fund(
    val fundId: String,
    val name: String,
    val currency: String = "SEK",
    val isin: String? = null,
)
