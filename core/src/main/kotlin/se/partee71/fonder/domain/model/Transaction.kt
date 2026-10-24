package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

enum class TransactionType { KOP, SALJ }

/**
 * En fondtransaktion (köp eller sälj).
 *
 * @param epochDay handelsdag som epoch-day (UTC), sorterbar utan tidszonsberoende.
 * @param shares antal andelar.
 * @param pricePerShare kurs (NAV) per andel vid transaktionen.
 * @param fee avgift i kr (t.ex. courtage), default 0.0. För ett köp ingår den i anskaffningsvärdet,
 *   för ett sälj dras den från försäljningsbeloppet (genomsnittsmetoden, se
 *   [se.partee71.fonder.domain.usecase] `RealizedGainCalculator`, issue #10, #192).
 * @param dateEstimated sant när [epochDay] inte är känt utan **uppskattades** av innehavsimporten ur
 *   kurshistoriken (`PurchaseDateEstimator`, eller importens reservdatum), IMP-2, issue #222. Rensas
 *   (falskt) när användaren rättar datumet. Default falskt: en manuellt registrerad transaktion och en
 *   avräkningsnota har ett riktigt datum.
 */
@Serializable
data class Transaction(
    val id: Long = 0,
    val fundId: String,
    val type: TransactionType,
    val epochDay: Long,
    val shares: Double,
    val pricePerShare: Double,
    val fee: Double = 0.0,
    val dateEstimated: Boolean = false
) {
    /** Transaktionens totalbelopp (positivt för köp, används med tecken i beräkningar). */
    val amount: Double get() = shares * pricePerShare
}
