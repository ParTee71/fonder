package se.partee71.fonder.data.room.entities

import androidx.room.Entity
import se.partee71.fonder.domain.model.SuggestionDismissal
import se.partee71.fonder.domain.model.SuggestionKind

/**
 * Ett avstått bytesförslag (HEM-14, Room 15→16) — se [SuggestionDismissal] för varför nyckeln är
 * fondparet och sorten och inte en inspelad rad. En rad per par: att avstå igen skriver över
 * datumet.
 *
 * `kind` lagras som text av samma skäl som [SuggestionRecordEntity.kind]; ett okänt värde läses
 * som [SuggestionKind.RISK_PLAN].
 */
@Entity(
    tableName = SuggestionDismissalEntity.TABLE_NAME,
    primaryKeys = ["sellIsin", "buyIsin", "kind"],
)
data class SuggestionDismissalEntity(
    val sellIsin: String,
    val buyIsin: String,
    val kind: String,
    val dismissedAtEpochDay: Long,
) {
    fun toDomain() = SuggestionDismissal(
        sellIsin = sellIsin,
        buyIsin = buyIsin,
        kind = runCatching { SuggestionKind.valueOf(kind) }.getOrDefault(SuggestionKind.RISK_PLAN),
        dismissedAtEpochDay = dismissedAtEpochDay,
    )

    companion object {
        /** Tabellnamnet — ett ställe, så backup-kontraktets ändringssignal (HEM-12) inte kan glida isär från schemat. */
        const val TABLE_NAME = "suggestion_dismissals"

        fun fromDomain(dismissal: SuggestionDismissal) = SuggestionDismissalEntity(
            sellIsin = dismissal.sellIsin,
            buyIsin = dismissal.buyIsin,
            kind = dismissal.kind.name,
            dismissedAtEpochDay = dismissal.dismissedAtEpochDay,
        )
    }
}
