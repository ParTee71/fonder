package se.partee71.fonder.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.partee71.fonder.data.room.daos.SuggestionRecordDao
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity
import se.partee71.fonder.domain.model.SuggestionRecord
import javax.inject.Inject
import javax.inject.Singleton

/** Kontrakt för bytesplanens facit-inspelning (HEM-8, issue #70) — se [SuggestionRecord]. */
interface SuggestionRecordRepository {
    fun observeAll(): Flow<List<SuggestionRecord>>

    /** Sant om det här bytet redan spelats in [epochDay] — dedupspärr mot upprepad inspelning samma dag (se [se.partee71.fonder.worker.FundPriceUpdateWorker]). */
    suspend fun hasRecordedToday(sellIsin: String, buyIsin: String, epochDay: Long): Boolean

    suspend fun record(record: SuggestionRecord)
}

@Singleton
class RoomSuggestionRecordRepository @Inject constructor(
    private val dao: SuggestionRecordDao,
) : SuggestionRecordRepository {

    override fun observeAll(): Flow<List<SuggestionRecord>> =
        dao.observeAll().map { list -> list.map(SuggestionRecordEntity::toDomain) }

    override suspend fun hasRecordedToday(sellIsin: String, buyIsin: String, epochDay: Long): Boolean =
        dao.existsForDay(sellIsin, buyIsin, epochDay)

    override suspend fun record(record: SuggestionRecord) {
        dao.insert(SuggestionRecordEntity.fromDomain(record))
    }
}
