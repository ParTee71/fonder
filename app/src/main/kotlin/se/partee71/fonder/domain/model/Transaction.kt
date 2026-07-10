package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

enum class TransactionType { KOP, SALJ }

/**
 * En fondtransaktion (köp eller sälj).
 *
 * @param epochDay handelsdag som epoch-day (UTC), sorterbar utan tidszonsberoende.
 * @param shares antal andelar.
 * @param pricePerShare kurs (NAV) per andel vid transaktionen.
 * @param fee avgift i kr (t.ex. courtage), default 0.0. Dras av från försäljningsbeloppet
 *   vid realisationsberäkning för sälj-transaktioner (se [se.partee71.fonder.domain.usecase]
 *   `RealizedGainCalculator`, issue #10) — räknas inte in i anskaffningsvärdet för köp.
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
) {
    /** Transaktionens totalbelopp (positivt för köp, används med tecken i beräkningar). */
    val amount: Double get() = shares * pricePerShare
}
