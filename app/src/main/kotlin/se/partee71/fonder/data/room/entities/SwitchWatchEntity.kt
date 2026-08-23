package se.partee71.fonder.data.room.entities

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCandidateSource
import se.partee71.fonder.domain.model.SwitchWatchCloseReason

/**
 * Ett pågående byte (ANA-12, issue #114) — se [SwitchWatch] för varför det här är genuin,
 * backup-bärande användardata och inte cache.
 */
@Entity(tableName = "switch_watches")
data class SwitchWatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sellIsin: String,
    val sellFundName: String,
    val soldAtEpochDay: Long,
    val proceedsKr: Double?,
    val targetLevel: Int?,
    val sourceRecordId: Long?,
    val closedAtEpochDay: Long?,
    val boughtIsin: String?,
    /**
     * [SwitchWatchCloseReason] som text, null medan bevakningen är öppen — lagrad som `String`
     * av samma skäl som `SuggestionRecordEntity.kind`: en `TypeConverter` hade lagt en global
     * konvertering på hela databasen för en enda kolumn. Okänt värde läses som
     * [SwitchWatchCloseReason.AVBRUTEN]: en fil från en nyare version ska degradera, inte krascha.
     */
    val closeReason: String?,
)

/**
 * En bevakad kandidat (ANA-13). Kaskadraderas med sin bevakning — en kandidat utan bevakning är
 * inte "data som blev kvar", den är en rad ingen läsare kan tolka.
 */
@Entity(
    tableName = "switch_watch_candidates",
    foreignKeys = [
        ForeignKey(
            entity = SwitchWatchEntity::class,
            parentColumns = ["id"],
            childColumns = ["watchId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("watchId")],
)
data class SwitchWatchCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val watchId: Long,
    val isin: String,
    val name: String,
    val navAtStart: Double?,
    val navAtStartEpochDay: Long?,
    /** [SwitchWatchCandidateSource] som text — se [SwitchWatchEntity.closeReason] för varför. */
    val source: String = SwitchWatchCandidateSource.AUTO.name,
    val position: Int = 0,
) {
    fun toDomain() = SwitchWatchCandidate(
        id = id,
        watchId = watchId,
        isin = isin,
        name = name,
        navAtStart = navAtStart,
        navAtStartEpochDay = navAtStartEpochDay,
        source = runCatching { SwitchWatchCandidateSource.valueOf(source) }
            .getOrDefault(SwitchWatchCandidateSource.AUTO),
        position = position,
    )

    companion object {
        fun fromDomain(candidate: SwitchWatchCandidate, watchId: Long = candidate.watchId) =
            SwitchWatchCandidateEntity(
                id = candidate.id,
                watchId = watchId,
                isin = candidate.isin,
                name = candidate.name,
                navAtStart = candidate.navAtStart,
                navAtStartEpochDay = candidate.navAtStartEpochDay,
                source = candidate.source.name,
                position = candidate.position,
            )
    }
}

/**
 * En bevakning med sina kandidater — läsvyn varje skärm använder. Room fyller [candidates] via
 * [Relation]; ordningen sätts här i stället för i SQL eftersom `@Relation` inte tar en
 * `ORDER BY` som gäller den nästlade listan.
 */
data class SwitchWatchWithCandidates(
    @Embedded val watch: SwitchWatchEntity,
    @Relation(parentColumn = "id", entityColumn = "watchId")
    val candidates: List<SwitchWatchCandidateEntity>,
) {
    fun toDomain() = SwitchWatch(
        id = watch.id,
        sellIsin = watch.sellIsin,
        sellFundName = watch.sellFundName,
        soldAtEpochDay = watch.soldAtEpochDay,
        proceedsKr = watch.proceedsKr,
        targetLevel = watch.targetLevel,
        sourceRecordId = watch.sourceRecordId,
        closedAtEpochDay = watch.closedAtEpochDay,
        boughtIsin = watch.boughtIsin,
        closeReason = watch.closeReason?.let { reason ->
            runCatching { SwitchWatchCloseReason.valueOf(reason) }.getOrDefault(SwitchWatchCloseReason.AVBRUTEN)
        },
        candidates = candidates
            .sortedWith(compareBy({ it.position }, { it.id }))
            .map(SwitchWatchCandidateEntity::toDomain),
    )

    companion object {
        fun fromDomain(watch: SwitchWatch) = SwitchWatchWithCandidates(
            watch = SwitchWatchEntity(
                id = watch.id,
                sellIsin = watch.sellIsin,
                sellFundName = watch.sellFundName,
                soldAtEpochDay = watch.soldAtEpochDay,
                proceedsKr = watch.proceedsKr,
                targetLevel = watch.targetLevel,
                sourceRecordId = watch.sourceRecordId,
                closedAtEpochDay = watch.closedAtEpochDay,
                boughtIsin = watch.boughtIsin,
                closeReason = watch.closeReason?.name,
            ),
            candidates = watch.candidates.map { SwitchWatchCandidateEntity.fromDomain(it, watch.id) },
        )
    }
}
