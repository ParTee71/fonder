package se.partee71.fonder.data.room.entities

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import se.partee71.fonder.domain.model.SuggestionCandidate
import se.partee71.fonder.domain.model.SuggestionRecord

/**
 * En köpkandidat sparad med ett bytesplansförslag (HEM-8, issue #239, Room 21→22) — se
 * [SuggestionCandidate]. Kaskadraderas med sitt förslag, samma mönster som
 * [SwitchWatchCandidateEntity]: en kandidat utan förslag är en rad ingen läsare kan tolka, och
 * gallringen ([se.partee71.fonder.data.repository.SuggestionRecordRepository.prune]) tar då
 * kandidaterna med sig utan en egen fråga.
 */
@Entity(
    tableName = SuggestionCandidateEntity.TABLE_NAME,
    foreignKeys = [
        ForeignKey(
            entity = SuggestionRecordEntity::class,
            parentColumns = ["id"],
            childColumns = ["recordId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("recordId")]
)
data class SuggestionCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordId: Long,
    val isin: String,
    val name: String,
    val position: Int,
    val totalFee: Double?,
    val risk: Int?,
    val category: String?,
    val fundType: String?,
    val companyName: String?,
    val developmentOneYear: Double?
) {
    fun toDomain() = SuggestionCandidate(
        id = id,
        recordId = recordId,
        isin = isin,
        name = name,
        position = position,
        totalFee = totalFee,
        risk = risk,
        category = category,
        fundType = fundType,
        companyName = companyName,
        developmentOneYear = developmentOneYear
    )

    companion object {
        /** Tabellnamnet — ett ställe, så backup-kontraktets ändringssignal (HEM-12) inte kan glida isär från schemat. */
        const val TABLE_NAME = "suggestion_candidates"

        /** [recordId] är förslagets id i databasen — kandidatens eget kan vara 0 före insättningen. */
        fun fromDomain(candidate: SuggestionCandidate, recordId: Long) = SuggestionCandidateEntity(
            id = 0,
            recordId = recordId,
            isin = candidate.isin,
            name = candidate.name,
            position = candidate.position,
            totalFee = candidate.totalFee,
            risk = candidate.risk,
            category = candidate.category,
            fundType = candidate.fundType,
            companyName = candidate.companyName,
            developmentOneYear = candidate.developmentOneYear
        )
    }
}

/**
 * Ett förslag med sina kandidater — läsvyn för varje fråga som ger [SuggestionRecord]. Ordningen
 * sätts här och inte i SQL, eftersom `@Relation` inte tar en `ORDER BY` för den nästlade listan
 * (samma som [SwitchWatchWithCandidates]).
 */
data class SuggestionRecordWithCandidates(
    @Embedded val record: SuggestionRecordEntity,
    @Relation(parentColumn = "id", entityColumn = "recordId")
    val candidates: List<SuggestionCandidateEntity>
) {
    fun toDomain(): SuggestionRecord = record.toDomain().copy(
        candidates = candidates
            .sortedWith(compareBy({ it.position }, { it.id }))
            .map(SuggestionCandidateEntity::toDomain)
    )
}
