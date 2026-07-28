package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/**
 * En fond i användarens bevakning/portfölj.
 *
 * Identitet: [fundId] är normalt fondlista-plattformens egen kod (t.ex. "SHB0000627").
 * Fonder som matchats enbart via ISIN (`FundPriceRepository.findFundByIsin`, TP-13/TP-14)
 * saknar en sådan kod och får i stället **ISIN:et självt** som [fundId].
 *
 * [isin] är ett separat, valfritt attribut (saknas för fonder tillagda via fondsök tills
 * ett bekräftats — se [se.partee71.fonder.domain.usecase] och `FondDetaljViewModel`) som
 * används för att hämta full kurshistorik sedan köpdatum från ISIN-baserade källor
 * (Avanza m.fl., se KRAVLISTA TP-14).
 *
 * [fondlistaFundId] är fondlista-plattformens kod för en fond vars [fundId] är ett ISIN —
 * uppslagen i efterhand via namnkandidat + ISIN-verifiering (issue #39). Den används
 * **bara** som nyckel vid kurshämtning; fondens identitet och transaktionernas koppling
 * följer fortfarande [fundId]. Null betyder "inte uppslaget, eller finns inte i katalogen".
 * Härledd cache-data: går den förlorad slås den bara upp igen.
 */
@Serializable
data class Fund(
    val fundId: String,
    val name: String,
    val currency: String = "SEK",
    val isin: String? = null,
    val fondlistaFundId: String? = null,
)
