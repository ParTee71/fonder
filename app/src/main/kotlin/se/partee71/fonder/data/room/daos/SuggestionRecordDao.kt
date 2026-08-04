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
        """,
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
        """,
    )
    fun observeHistory(): Flow<List<SuggestionRecordEntity>>

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
        """,
    )
    suspend fun existsForDay(sellIsin: String, buyIsin: String, epochDay: Long, kind: String): Boolean

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
