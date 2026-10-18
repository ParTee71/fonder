package se.partee71.fonder.data.repository

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord

/**
 * In-memory-fake av [SuggestionRecordRepository] för läsarna av bytesplanen (HEM-8) — Översikten,
 * Beslut och [se.partee71.fonder.domain.usecase.PendingDecisionsSource]. Speglar DAO:ns SQL där
 * det spelar roll: [observeLatestBatch] är bytesplanens senaste dygns **senaste körning**, sorterad på
 * plats i planen, och [observeHistory] nyast först.
 */
class FakeSuggestionRecordRepository : SuggestionRecordRepository {

    val records = MutableStateFlow<List<SuggestionRecord>>(emptyList())

    override fun observeLatestBatch(): Flow<List<SuggestionRecord>> = records.map { stored ->
        // Som DAO:n: bara planens rader, även när dygn och körning väljs (issue #91).
        val all = stored.filter { it.kind == SuggestionKind.RISK_PLAN }
        if (all.isEmpty()) return@map emptyList()
        val latestDay = all.maxOf { it.suggestedAtEpochDay }
        val sameDay = all.filter { it.suggestedAtEpochDay == latestDay }
        val latestBatch = sameDay.maxOf { it.batchEpochMillis }
        sameDay.filter { it.batchEpochMillis == latestBatch }.sortedBy { it.planIndex }
    }

    /** Antal gånger hela historiken begärts — de riktade uppslagen ([observeByIds]) ska inte gå den vägen. */
    var observeHistoryCalls = 0

    override fun observeHistory(): Flow<List<SuggestionRecord>> {
        observeHistoryCalls++
        return records.map { all ->
            all.sortedWith(compareByDescending<SuggestionRecord> { it.suggestedAtEpochDay }.thenByDescending { it.batchEpochMillis }.thenBy { it.planIndex })
        }
    }

    /** Riktat, som Room — inte via [observeHistory] (defaulten i gränssnittet är bara för enkla fakes). */
    override fun observeByIds(ids: Set<Long>): Flow<List<SuggestionRecord>> = records.map { all -> all.filter { it.id in ids } }

    override suspend fun setFollowed(id: Long, followed: Boolean) {
        records.value = records.value.map { record -> if (record.id == id) record.copy(followed = followed) else record }
    }

    override suspend fun record(record: SuggestionRecord) {
        records.value += record
    }

    override suspend fun commitBatch(moves: List<SuggestionRecordRepository.BatchMove>, records: List<SuggestionRecord>) {
        val moved = this.records.value.map { record ->
            moves.firstOrNull { it.id == record.id }
                ?.let { record.copy(batchEpochMillis = it.batchEpochMillis, planIndex = it.planIndex, switchValueKr = it.switchValueKr) }
                ?: record
        }
        this.records.value = moved + records
    }

    override suspend fun hasRecordedToday(sellIsin: String, buyIsin: String, epochDay: Long, kind: SuggestionKind): Boolean = false

    /** Dagen senaste gallringen gjordes mot — null om ingen gjorts. */
    var prunedBefore: LocalDate? = null

    override suspend fun prune(today: LocalDate) {
        prunedBefore = today
    }
}
