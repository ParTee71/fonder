package se.partee71.fonder.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.partee71.fonder.data.room.daos.SuggestionRecordDao
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity
import se.partee71.fonder.domain.model.SuggestionRecord
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Kontrakt för bytesplanens facit-inspelning (HEM-8, issue #70) — se [SuggestionRecord]. */
interface SuggestionRecordRepository {

    /** Den senast inspelade **körningens** rader, sorterade på plats i planen — det Hem visar. */
    fun observeLatestBatch(): Flow<List<SuggestionRecord>>

    /** Sant om det här bytet redan spelats in [epochDay] — dedupspärr mot upprepad inspelning samma dag (se [se.partee71.fonder.worker.FundPriceUpdateWorker]). */
    suspend fun hasRecordedToday(sellIsin: String, buyIsin: String, epochDay: Long): Boolean

    suspend fun record(record: SuggestionRecord)

    /**
     * Tar bort förslag äldre än [RETENTION_DAYS]. Facit behöver historik för att kunna
     * utvärderas, så det här är ett **tak mot obegränsad tillväxt** — inte en gallring av
     * användbar data: tabellen ingår i backup-kontraktet (NFR-1) och skulle annars växa med
     * varje backstop-körning för alltid, och därmed även den framtida backup-payloaden
     * (issue #75, punkt 6).
     */
    suspend fun prune(today: LocalDate)

    companion object {
        /** Två år räcker med marginal för att utvärdera ett förslag mot att ha behållit innehavet. */
        const val RETENTION_DAYS = 730L
    }
}

@Singleton
class RoomSuggestionRecordRepository @Inject constructor(
    private val dao: SuggestionRecordDao,
) : SuggestionRecordRepository {

    override fun observeLatestBatch(): Flow<List<SuggestionRecord>> =
        dao.observeLatestBatch().map { list -> list.map(SuggestionRecordEntity::toDomain) }

    override suspend fun hasRecordedToday(sellIsin: String, buyIsin: String, epochDay: Long): Boolean =
        dao.existsForDay(sellIsin, buyIsin, epochDay)

    override suspend fun record(record: SuggestionRecord) {
        dao.insert(SuggestionRecordEntity.fromDomain(record))
    }

    override suspend fun prune(today: LocalDate) {
        dao.deleteOlderThan(today.toEpochDay() - SuggestionRecordRepository.RETENTION_DAYS)
    }
}
