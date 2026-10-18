package se.partee71.fonder.data.room.daos

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity

@Dao
interface SuggestionRecordDao {

    /**
     * Raderna från den **senast inspelade körningen** — den enda batch Hem visar (HEM-8).
     * Filtreringen ligger i SQL i stället för i minnet: tabellen växer med varje
     * backstop-körning, och att materialisera hela historiken vid varje emission på Hem för
     * att plocka ut tre rader är slöseri som bara blir värre med tiden (issue #75, punkt 6).
     *
     * Senaste dygnet räcker inte som nyckel — backstopen kör var 12:e timme, så två körningar
     * landar normalt samma dygn. Därav `batchEpochMillis` (issue #75, fynd B).
     *
     * `kind = 'RISK_PLAN'` sitter i **alla tre** nivåerna, inte bara den yttersta (issue #91):
     * annars väljer `MAX()`-underfrågorna dygn och körning utifrån rader frågan sedan filtrerar
     * bort. Vanligaste dygnet är just det där planen inte gav något (ingen nivå avviker ≥
     * `MIN_GAP_PP`) men avgiftsskanningen spelade in en rad — utan filtret hade Hem då visat
     * ett avgiftsbyte som "1. Sälj X → Köp Y" i en plan det aldrig ingick i.
     */
    @Query(
        """
        SELECT * FROM suggestion_records
        WHERE kind = 'RISK_PLAN'
          AND suggestedAtEpochDay = (SELECT MAX(suggestedAtEpochDay) FROM suggestion_records WHERE kind = 'RISK_PLAN')
          AND batchEpochMillis = (
              SELECT MAX(batchEpochMillis) FROM suggestion_records
              WHERE kind = 'RISK_PLAN'
                AND suggestedAtEpochDay = (SELECT MAX(suggestedAtEpochDay) FROM suggestion_records WHERE kind = 'RISK_PLAN')
          )
        ORDER BY planIndex ASC, id ASC
        """
    )
    fun observeLatestBatch(): Flow<List<SuggestionRecordEntity>>

    /**
     * Hela historiken, nyast först (SET-5, issue #80) — facit-vyns läsväg, till skillnad från
     * [observeLatestBatch] som medvetet bara ger Hem den senaste körningen. Sorteringen är
     * dygn, sedan körning, sedan plats i planen, så en batch alltid hänger ihop i listan även
     * när två körningar landat samma dygn.
     */
    @Query(
        """
        SELECT * FROM suggestion_records
        ORDER BY suggestedAtEpochDay DESC, batchEpochMillis DESC, planIndex ASC
        """
    )
    fun observeHistory(): Flow<List<SuggestionRecordEntity>>

    /**
     * Förslag av sorten [kind] från och med [sinceEpochDay], nyast först — avgiftsbytena som Beslut
     * och flikmärket läser (ANA-9, NAV-9). Urvalet ligger i SQL av samma skäl som [observeLatestBatch]:
     * märket är aktivt hela tiden, och tabellen växer med varje backstop-körning.
     */
    @Query(
        """
        SELECT * FROM suggestion_records
        WHERE kind = :kind AND suggestedAtEpochDay >= :sinceEpochDay
        ORDER BY suggestedAtEpochDay DESC, batchEpochMillis DESC, id DESC
        """
    )
    fun observeRecent(kind: String, sinceEpochDay: Long): Flow<List<SuggestionRecordEntity>>

    /**
     * Markerar (eller avmarkerar) ett förslag som genomfört (HEM-8/SET-5, issue #80). Kolumnen
     * har funnits sedan tabellen skapades (Room 9→10) men saknade skrivväg — utan den kan facit
     * inte skilja rådets träffsäkerhet från det faktiska utfallet.
     */
    @Query("UPDATE suggestion_records SET followed = :followed WHERE id = :id")
    suspend fun setFollowed(id: Long, followed: Boolean)

    /**
     * Sant om exakt det här bytet (samma sälj-/köp-ISIN **och** samma [kind]) redan spelats in
     * [epochDay] — dedupspärr mot upprepad inspelning samma dag.
     *
     * Typen är en del av nyckeln sedan issue #91: samma fondpar kan legitimt föreslås både som
     * riskplansbyte och som avgiftsbyte, och utan typen hade den ena tyst blockerat den andra —
     * facit hade då saknat halva historien utan att någonstans säga det.
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM suggestion_records
            WHERE sellIsin = :sellIsin AND buyIsin = :buyIsin AND suggestedAtEpochDay = :epochDay AND kind = :kind
        )
        """
    )
    suspend fun existsForDay(sellIsin: String, buyIsin: String, epochDay: Long, kind: String): Boolean

    /**
     * Flyttar en rad till en ny körning (HEM-8, issue #143): när planen räknas om samma dag och ett
     * par står kvar byter raden körning och plats i stället för att spelas in en gång till — facit
     * (SET-5) räknar varje råd en gång per dag, och den senaste körningen är ändå komplett från
     * `planIndex 0`. Id, kvittering och NAV-utgångsläge behålls; beloppet följer den nya planen.
     */
    @Query("UPDATE suggestion_records SET batchEpochMillis = :batchEpochMillis, planIndex = :planIndex, switchValueKr = :switchValueKr WHERE id = :id")
    suspend fun moveToBatch(id: Long, batchEpochMillis: Long, planIndex: Int, switchValueKr: Double?)

    /** Raderna med [ids] — de öppna bevakningarnas källförslag (BYT-1), aldrig hela historiken. */
    @Query("SELECT * FROM suggestion_records WHERE id IN (:ids)")
    fun observeByIds(ids: List<Long>): Flow<List<SuggestionRecordEntity>>

    /**
     * Den **senaste** planraden per fondpar bland paren i [sellIsins] × [buyIsins] — de avstådda
     * förslagen (HEM-14) hämtas här, inte ur hela historiken: märkesflödet (NAV-9) är alltid aktivt
     * och tabellen växer med varje körning. Anroparen filtrerar till exakta par; frågan kan ge
     * korsade par när flera avståenden delar sälj- eller köpfond.
     */
    @Query(
        """
        SELECT * FROM suggestion_records r
        WHERE r.kind = 'RISK_PLAN' AND r.sellIsin IN (:sellIsins) AND r.buyIsin IN (:buyIsins)
          AND r.id = (
              SELECT s.id FROM suggestion_records s
              WHERE s.kind = 'RISK_PLAN' AND s.sellIsin = r.sellIsin AND s.buyIsin = r.buyIsin
              ORDER BY s.suggestedAtEpochDay DESC, s.batchEpochMillis DESC, s.id DESC
              LIMIT 1
          )
        ORDER BY r.planIndex ASC, r.id ASC
        """
    )
    fun observeLatestPlanRecordsFor(sellIsins: List<String>, buyIsins: List<String>): Flow<List<SuggestionRecordEntity>>

    @Insert
    suspend fun insert(record: SuggestionRecordEntity): Long

    /**
     * Hela tabellen. Ingen produktionsläsare — verifieringsfrågan för tester (SET-1:s
     * tömningsrundtur och migreringstesterna), där hela innehållet är själva påståendet.
     */
    @Query("SELECT * FROM suggestion_records")
    suspend fun getAll(): List<SuggestionRecordEntity>

    /** Tar bort förslag inspelade före [epochDay] — retention, se [se.partee71.fonder.data.repository.SuggestionRecordRepository.prune]. */
    @Query("DELETE FROM suggestion_records WHERE suggestedAtEpochDay < :epochDay")
    suspend fun deleteOlderThan(epochDay: Long)

    /** Töms tillsammans med fonder/transaktioner/kurser (SET-1) — inspelade förslag är användardata, inte cache. */
    @Query("DELETE FROM suggestion_records")
    suspend fun deleteAll()
}
