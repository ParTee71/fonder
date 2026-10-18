package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch

/**
 * Vilka planrader som är **sålda, väntar på köp** (BYT-1) — en regel för Beslut
 * ([PendingDecisionsSource]) och Fonddetalj (ANA-10), så de aldrig säger olika om samma rad.
 *
 * En rad är pågående när en **öppen** bevakning startats ur förslaget: samma inspelade rad, eller
 * samma fondpar i en tidigare inspelning (planen spelas in på nytt varje dag med nya id:n, och en
 * bevakning lever 14 dagar). En fristående bevakning av säljfonden (SLD-5) märker **inte** raden —
 * den hör till ett annat sälj och kan inte kvittera förslaget. En utgången bevakning (äldre än
 * `WATCH_TTL_DAYS`, ännu inte stängd) märker inte heller. Källförslagen slås upp riktat
 * ([SuggestionRecordRepository.observeByIds]), aldrig ur hela historiken.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InProgressSwitches internal constructor(
    private val switchWatchRepository: SwitchWatchRepository,
    private val suggestionRecordRepository: SuggestionRecordRepository,
    private val today: () -> LocalDate
) {
    @Inject
    constructor(
        switchWatchRepository: SwitchWatchRepository,
        suggestionRecordRepository: SuggestionRecordRepository
    ) : this(switchWatchRepository, suggestionRecordRepository, LocalDate::now)

    /**
     * De öppna bevakningarnas källförslag — som rad-id och som fondpar, plus själva raderna per id
     * ([sources]) så Beslut kan säga vilket förslag ett sålt byte kom ur (BYT-4).
     */
    data class Keys(
        val recordIds: Set<Long> = emptySet(),
        val pairs: Set<Pair<String, String>> = emptySet(),
        val sources: Map<Long, SuggestionRecord> = emptyMap()
    ) {
        fun marks(row: SwitchPlanResolver.Suggestion): Boolean = row.recordId in recordIds || (row.sellIsin to row.buyIsin) in pairs

        fun mark(plan: List<SwitchPlanResolver.Suggestion>): List<SwitchPlanResolver.Suggestion> = plan.map { it.copy(inProgress = marks(it)) }
    }

    /** Nycklarna ur en egen prenumeration på de öppna bevakningarna — Fonddetalj (ANA-10). */
    fun observe(): Flow<Keys> = switchWatchRepository.observeOpen().flatMapLatest { watches -> keysFor(watches, today()) }

    /**
     * Nycklarna ur en redan läst lista öppna bevakningar — [PendingDecisionsSource] delar en
     * prenumeration mellan Besluts bevakningslista och den här märkningen, så de aldrig ser
     * olika ögonblick av tabellen.
     */
    fun keysFor(openWatches: List<SwitchWatch>, today: LocalDate): Flow<Keys> {
        // En utgången bevakning beskriver inte längre ett pågående byte — som på Beslut.
        val ids = openWatches.filterNot { SwitchWatchCalc.isExpired(it, today) }.mapNotNullTo(mutableSetOf()) { it.sourceRecordId }
        return suggestionRecordRepository.observeByIds(ids).map { records ->
            Keys(recordIds = ids, pairs = records.mapTo(mutableSetOf()) { it.sellIsin to it.buyIsin }, sources = records.associateBy { it.id })
        }
    }
}
