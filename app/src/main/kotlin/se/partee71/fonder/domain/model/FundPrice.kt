package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/**
 * En daglig fondkurs (NAV), hämtad från handelsbanken.fondlista.se (se issue #2/#3).
 *
 * **[nav] är alltid i [VALUE_CURRENCY].** Hela värdekedjan (portföljvärde, vinst/förlust,
 * dag/vecka/månad) räknar i kronor utan någon valutakonvertering, så en kurs i en annan
 * valuta får aldrig hamna i cachen — den skulle tyst räknas som kronor och ge ett värde
 * som är fel med hela växelkursen (issue #41). Källor som levererar fondens egen valuta
 * filtreras därför i repository-lagret.
 */
@Serializable
data class FundPrice(
    val fundId: String,
    val epochDay: Long,
    val nav: Double,
    val currency: String = VALUE_CURRENCY,
) {
    companion object {
        /** Valutan appens värdeberäkningar förutsätter. Kurser i andra valutor cachas inte. */
        const val VALUE_CURRENCY = "SEK"
    }
}
