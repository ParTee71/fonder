package se.partee71.fonder.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import se.partee71.fonder.data.room.AppDatabase
import se.partee71.fonder.data.room.daos.SuggestionRecordDao
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity
import se.partee71.fonder.domain.model.SuggestionKind
import se.partee71.fonder.domain.model.SuggestionRecord
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Kontrakt för bytesplanens facit-inspelning (HEM-8, issue #70) — se [SuggestionRecord]. */
interface SuggestionRecordRepository {

    /** Den senast inspelade **körningens** rader, sorterade på plats i planen — det Hem visar. */
    fun observeLatestBatch(): Flow<List<SuggestionRecord>>

    /**
     * Hela inspelade historiken, nyast först — facit-vyns läsväg (SET-5, issue #80). Skild från
     * [observeLatestBatch] med flit: Hem ska bara se den senaste körningen, facit ska se allt.
     */
    fun observeHistory(): Flow<List<SuggestionRecord>>

    /**
     * Förslag av sorten [kind] inspelade [sinceEpochDay] eller senare, nyast först — Beslutets läsväg
     * för avgiftsbytena (ANA-9, NAV-9). Room filtrerar i SQL, så flikmärket inte materialiserar hela
     * historiken vid varje ändring; defaulten här är samma urval ur [observeHistory], för fakes.
     */
    fun observeRecent(kind: SuggestionKind, sinceEpochDay: Long): Flow<List<SuggestionRecord>> =
        observeHistory().map { all -> all.filter { it.kind == kind && it.suggestedAtEpochDay >= sinceEpochDay } }

    /**
     * Sant om det här bytet av den här sorten redan spelats in [epochDay] — dedupspärr mot
     * upprepad inspelning samma dag (se [se.partee71.fonder.worker.FundPriceUpdateWorker]).
     * [kind] ingår i nyckeln sedan issue #91: samma fondpar kan vara både ett riskplansbyte och
     * ett avgiftsbyte, och den ena ska inte tyst blockera den andra.
     */
    suspend fun hasRecordedToday(sellIsin: String, buyIsin: String, epochDay: Long, kind: SuggestionKind): Boolean

    suspend fun record(record: SuggestionRecord)

    /** En inspelad planrad som flyttas till en ny körning — se [commitBatch]. */
    data class BatchMove(val id: Long, val batchEpochMillis: Long, val planIndex: Int, val switchValueKr: Double?)

    /**
     * Skriver en omräknad plan (HEM-8, issue #143) i **en** transaktion: rader för par som redan
     * spelats in i dag flyttas till den nya körningen ([moves] — samma rad, samma kvittering, så
     * facit räknar rådet en gång), nya par spelas in ([records]). Atomärt, så en avbruten
     * skrivning aldrig lämnar en körning utan sitt steg 0.
     */
    suspend fun commitBatch(moves: List<BatchMove>, records: List<SuggestionRecord>)

    /** Raderna med [ids] — de öppna bevakningarnas källförslag (BYT-1). Defaulten här är urvalet ur [observeHistory], för fakes. */
    fun observeByIds(ids: Set<Long>): Flow<List<SuggestionRecord>> =
        if (ids.isEmpty()) flowOf(emptyList()) else observeHistory().map { all -> all.filter { it.id in ids } }

    /**
     * Den senaste planraden ([SuggestionKind.RISK_PLAN]) per fondpar i [pairs] (sälj-ISIN, köp-ISIN)
     * — de avstådda förslagens rader (HEM-14). Room frågar riktat i SQL så märkesflödet (NAV-9)
     * aldrig materialiserar historiken; defaulten här är samma urval ur [observeHistory], för fakes.
     */
    fun observeLatestPlanRecordsFor(pairs: Set<Pair<String, String>>): Flow<List<SuggestionRecord>> =
        if (pairs.isEmpty()) {
            flowOf(emptyList())
        } else {
            observeHistory().map { all -> latestPlanRecordPerPair(all).filter { (it.sellIsin to it.buyIsin) in pairs } }
        }

    /**
     * Markerar ett förslag som genomfört eller ej (HEM-8/SET-5, issue #80) — det som gör att
     * facit kan mäta *följda* råd separat från alla givna råd. Ett oföljt förslag är ett
     * hypotetiskt utfall, ett följt ett verkligt; slås de ihop mäter siffran ingetdera.
     */
    suspend fun setFollowed(id: Long, followed: Boolean)

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

        /** Den senaste planraden per fondpar, i planordning — samma urval som DAO:ns `observeLatestPlanRecordsFor`. */
        fun latestPlanRecordPerPair(records: List<SuggestionRecord>): List<SuggestionRecord> =
            records
                .filter { it.kind == SuggestionKind.RISK_PLAN }
                .sortedWith(
                    compareByDescending<SuggestionRecord> { it.suggestedAtEpochDay }
                        .thenByDescending { it.batchEpochMillis }
                        .thenByDescending { it.id },
                )
                .distinctBy { it.sellIsin to it.buyIsin }
                .sortedWith(compareBy({ it.planIndex }, { it.id }))
    }
}

@Singleton
class RoomSuggestionRecordRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: SuggestionRecordDao,
) : SuggestionRecordRepository {

    override fun observeLatestBatch(): Flow<List<SuggestionRecord>> =
        dao.observeLatestBatch().map { list -> list.map(SuggestionRecordEntity::toDomain) }

    override fun observeHistory(): Flow<List<SuggestionRecord>> =
        dao.observeHistory().map { list -> list.map(SuggestionRecordEntity::toDomain) }

    override fun observeRecent(kind: SuggestionKind, sinceEpochDay: Long): Flow<List<SuggestionRecord>> =
        dao.observeRecent(kind.name, sinceEpochDay).map { list -> list.map(SuggestionRecordEntity::toDomain) }

    override suspend fun hasRecordedToday(sellIsin: String, buyIsin: String, epochDay: Long, kind: SuggestionKind): Boolean =
        dao.existsForDay(sellIsin, buyIsin, epochDay, kind.name)

    override suspend fun record(record: SuggestionRecord) {
        dao.insert(SuggestionRecordEntity.fromDomain(record))
    }

    override suspend fun commitBatch(moves: List<SuggestionRecordRepository.BatchMove>, records: List<SuggestionRecord>) {
        database.withTransaction {
            moves.forEach { dao.moveToBatch(it.id, it.batchEpochMillis, it.planIndex, it.switchValueKr) }
            records.forEach { dao.insert(SuggestionRecordEntity.fromDomain(it)) }
        }
    }

    override fun observeByIds(ids: Set<Long>): Flow<List<SuggestionRecord>> =
        if (ids.isEmpty()) flowOf(emptyList()) else dao.observeByIds(ids.toList()).map { list -> list.map(SuggestionRecordEntity::toDomain) }

    override fun observeLatestPlanRecordsFor(pairs: Set<Pair<String, String>>): Flow<List<SuggestionRecord>> =
        if (pairs.isEmpty()) {
            flowOf(emptyList())
        } else {
            dao.observeLatestPlanRecordsFor(pairs.map { it.first }.distinct(), pairs.map { it.second }.distinct())
                .map { list -> list.map(SuggestionRecordEntity::toDomain).filter { (it.sellIsin to it.buyIsin) in pairs } }
        }

    override suspend fun setFollowed(id: Long, followed: Boolean) {
        dao.setFollowed(id, followed)
    }

    override suspend fun prune(today: LocalDate) {
        dao.deleteOlderThan(today.toEpochDay() - SuggestionRecordRepository.RETENTION_DAYS)
    }
}
