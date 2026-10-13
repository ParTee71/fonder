package se.partee71.fonder.data.room.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord

/**
 * Facit-inspelningen för bytesplanen (HEM-8, issue #70) — se [SuggestionRecord] för varför
 * det här är genuin, backup-bärande användardata till skillnad från `fund_metadata`.
 *
 * Indexet på (kind, sellIsin, buyIsin) bär "senaste raden per fondpar"
 * ([se.partee71.fonder.data.room.daos.SuggestionRecordDao.observeLatestPlanRecordsFor], HEM-14):
 * frågan är ett flöde som körs om vid varje skrivning i tabellen och slår upp paret per rad.
 */
@Entity(
    tableName = SuggestionRecordEntity.TABLE_NAME,
    indices = [Index("kind", "sellIsin", "buyIsin")],
)
data class SuggestionRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val suggestedAtEpochDay: Long,
    val planIndex: Int,
    val sellIsin: String,
    val buyIsin: String,
    val sellNavAtSuggestion: Double,
    val buyNavAtSuggestion: Double,
    val switchValueKr: Double?,
    val followed: Boolean?,
    /** Körningen raden hör till — se [SuggestionRecord.batchEpochMillis]. 0 = inspelad före issue #75. */
    val batchEpochMillis: Long = 0,
    /**
     * [SuggestionKind] som text (issue #91) — lagrad som `String` i stället för via en
     * `TypeConverter` på hela databasen: kolumnen har ett default i SQL (`'RISK_PLAN'`, se
     * migreringen 12→13), och en converter hade lagt till en global konvertering för en enda
     * kolumn. Okänt värde läses som [SuggestionKind.RISK_PLAN] — en fil från en nyare version
     * ska degradera, inte krascha.
     */
    val kind: String = SuggestionKind.RISK_PLAN.name,
) {
    fun toDomain() = SuggestionRecord(
        id = id,
        suggestedAtEpochDay = suggestedAtEpochDay,
        planIndex = planIndex,
        sellIsin = sellIsin,
        buyIsin = buyIsin,
        sellNavAtSuggestion = sellNavAtSuggestion,
        buyNavAtSuggestion = buyNavAtSuggestion,
        switchValueKr = switchValueKr,
        followed = followed,
        batchEpochMillis = batchEpochMillis,
        kind = runCatching { SuggestionKind.valueOf(kind) }.getOrDefault(SuggestionKind.RISK_PLAN),
    )

    companion object {
        /** Tabellnamnet — ett ställe, så backup-kontraktets ändringssignal (HEM-12) inte kan glida isär från schemat. */
        const val TABLE_NAME = "suggestion_records"

        fun fromDomain(record: SuggestionRecord) = SuggestionRecordEntity(
            id = record.id,
            suggestedAtEpochDay = record.suggestedAtEpochDay,
            planIndex = record.planIndex,
            sellIsin = record.sellIsin,
            buyIsin = record.buyIsin,
            sellNavAtSuggestion = record.sellNavAtSuggestion,
            buyNavAtSuggestion = record.buyNavAtSuggestion,
            switchValueKr = record.switchValueKr,
            followed = record.followed,
            batchEpochMillis = record.batchEpochMillis,
            kind = record.kind.name,
        )
    }
}
