package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/**
 * Att användaren **avstått** från ett bytesförslag (HEM-14) — nycklat på fondparet och sorten,
 * inte på en inspelad [SuggestionRecord].
 *
 * Nyckeln är paret med flit: bakgrundskörningen spelar in planen på nytt varje dygn, med en ny
 * rad (nytt id) för samma sälj → köp. En flagga på raden hade alltså försvunnit nästa dag och
 * förslaget kommit tillbaka. Paret överlever ominspelningen och gallringen av gamla rader.
 *
 * Genuin användardata — ett val som inte går att räkna fram i efterhand — och därför med i
 * backup-kontraktet (NFR-1), se [se.partee71.fonder.data.repository.BackupPayload].
 *
 * @param dismissedAtEpochDay dagen användaren avstod. Avståendet gäller i
 *   [se.partee71.fonder.domain.usecase.SuggestionDismissals.TTL_DAYS] dagar; därefter visas
 *   förslaget igen om planen fortfarande ger det.
 */
@Serializable
data class SuggestionDismissal(
    val sellIsin: String,
    val buyIsin: String,
    val kind: SuggestionKind = SuggestionKind.RISK_PLAN,
    val dismissedAtEpochDay: Long,
)
