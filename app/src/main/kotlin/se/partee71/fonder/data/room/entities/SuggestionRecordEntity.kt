package se.partee71.fonder.data.room.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import se.partee71.fonder.domain.model.SuggestionRecord

/**
 * Facit-inspelningen för bytesplanen (HEM-8, issue #70) — se [SuggestionRecord] för varför
 * det här är genuin, backup-bärande användardata till skillnad från `fund_metadata`.
 */
@Entity(tableName = "suggestion_records")
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
    )

    companion object {
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
        )
    }
}
