package se.partee71.fonder.data.repository

import androidx.room.InvalidationTracker
import androidx.room.withTransaction
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.room.AppDatabase
import se.partee71.fonder.data.room.daos.FundDao
import se.partee71.fonder.data.room.daos.SuggestionDismissalDao
import se.partee71.fonder.data.room.daos.SuggestionRecordDao
import se.partee71.fonder.data.room.daos.SwitchWatchDao
import se.partee71.fonder.data.room.daos.TransactionDao
import se.partee71.fonder.data.room.entities.FundEntity
import se.partee71.fonder.data.room.entities.SuggestionDismissalEntity
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity
import se.partee71.fonder.data.room.entities.SwitchWatchCandidateEntity
import se.partee71.fonder.data.room.entities.SwitchWatchEntity
import se.partee71.fonder.data.room.entities.SwitchWatchWithCandidates
import se.partee71.fonder.data.room.entities.TransactionEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kontrakt för backup/restore av all användardata (regel 1 — datasäkerhet, NFR-1).
 *
 * Kontraktet är en **sträng**, inte en fil eller en `Uri`: transporten hör inte hemma här.
 * [LocalBackupRepository] låter UI:t skriva strängen till en användarvald fil via SAF (SET-6),
 * och Drive `appDataFolder` (TP-7 steg 2) kan senare skriva samma sträng till molnet utan att
 * formatet eller den här kedjan ändras.
 *
 * Innehållet definieras av [BackupPayload]; [BackupSerializer] äger formatet.
 */
interface BackupRepository {

    /** Serialiserar hela kontraktet. Fel fångas och returneras, aldrig kastade. */
    suspend fun export(): Result<String>

    /**
     * Kontraktets innehåll just nu, oserialiserat — samma läsning som [export] gör. Underlaget
     * för fingeravtrycket ([BackupFingerprint]) som Hems påminnelse (HEM-12) jämför mot.
     */
    suspend fun snapshot(): BackupPayload

    /**
     * Signalerar (inte nödvändigtvis bara) när något som ingår i kontraktet kan ha ändrats, med
     * en första signal direkt. Grov med flit: anroparen räknar om [snapshot] och avgör själv om
     * innehållet faktiskt skiljer sig — då behövs ingen krok i varje skrivväg.
     */
    fun observeChanges(): Flow<Unit>

    /**
     * Läser in en säkerhetskopia och **ersätter** kontraktets data med den. Ersättning, inte
     * sammanslagning: det här är en återställning, inte en import — merge skulle dubblera
     * transaktioner utan väg tillbaka, och importflödena (IMP-1/IMP-6) täcker redan det fallet.
     */
    suspend fun restore(json: String): Result<RestoreSummary>
}

/**
 * Backup mot en lokal fil (SET-6, issue #82) — steg 1 av TP-7.
 *
 * Cache-tabellerna (`fund_prices`, `fx_rates`, `fund_metadata`) rörs inte i någon riktning: de
 * ingår inte i filen och töms inte vid återställning. En återställd fond får därmed behålla den
 * kurshistorik som redan finns lokalt, och det som saknas hämtas om av den vanliga
 * bakgrundsuppdateringen.
 */
@Singleton
class LocalBackupRepository @Inject constructor(
    private val database: AppDatabase,
    private val fundDao: FundDao,
    private val transactionDao: TransactionDao,
    private val suggestionRecordDao: SuggestionRecordDao,
    private val switchWatchDao: SwitchWatchDao,
    private val suggestionDismissalDao: SuggestionDismissalDao,
    private val preferences: PreferencesRepository,
) : BackupRepository {

    override suspend fun export(): Result<String> = runCatching { BackupSerializer.encode(snapshot()) }

    override suspend fun snapshot(): BackupPayload = BackupPayload(
        exportedAtEpochMillis = System.currentTimeMillis(),
        funds = fundDao.getAll().map(FundEntity::toDomain),
        transactions = transactionDao.getAll().map(TransactionEntity::toDomain),
        suggestionRecords = suggestionRecordDao.getAll().map(SuggestionRecordEntity::toDomain),
        switchWatches = switchWatchDao.getAll().map(SwitchWatchWithCandidates::toDomain),
        suggestionDismissals = suggestionDismissalDao.getAll().map(SuggestionDismissalEntity::toDomain),
        riskProfile = preferences.riskProfile.first(),
        accountType = preferences.accountType.first(),
        themeMode = preferences.themeMode.first(),
        chosenBenchmarkIsin = preferences.chosenBenchmarkIsin.first(),
    )

    /**
     * Room-halvan via [InvalidationTracker] på kontraktets tabeller — bara en signal, ingen
     * läsning av raderna — och DataStore-halvan via kontraktets fyra inställningar. Varje källa
     * ger en första signal, så anroparen bör `debounce`:a.
     */
    override fun observeChanges(): Flow<Unit> = merge(
        callbackFlow {
            val observer = object : InvalidationTracker.Observer(CONTRACT_TABLES) {
                override fun onInvalidated(tables: Set<String>) {
                    trySend(Unit)
                }
            }
            database.invalidationTracker.addObserver(observer)
            send(Unit)
            awaitClose { database.invalidationTracker.removeObserver(observer) }
        },
        preferences.riskProfile.map { },
        preferences.accountType.map { },
        preferences.themeMode.map { },
        preferences.chosenBenchmarkIsin.map { },
    )

    /**
     * Room-halvan körs i **en** transaktion, så en avbruten återställning inte kan lämna
     * transaktioner utan sina fonder. DataStore är ett eget lager och kan inte ingå i samma
     * transaktion; den skrivs därför efter databasen — misslyckas den har användaren kvar sin
     * data och behöver bara sätta om ett par inställningar, medan motsatt ordning hade gett
     * återställda inställningar till en portfölj som inte kom fram.
     *
     * Raderna sätts in med **bevarade id:n**. Facit (SET-5) refererar fonder via ISIN och inte
     * via transaktions-id, men ett id som ändras vid varje återställning gör en öppen skärms
     * "ta bort rad" tvetydig — och att bevara dem kostar ingenting.
     */
    override suspend fun restore(json: String): Result<RestoreSummary> {
        val payload = BackupSerializer.decode(json).getOrElse { return Result.failure(it) }

        return runCatching {
            database.withTransaction {
                transactionDao.deleteAll()
                suggestionRecordDao.deleteAll()
                suggestionDismissalDao.deleteAll()
                // Kandidaterna följer med sin bevakning via ON DELETE CASCADE (Room 14→15) —
                // en egen tömning här hade varit en andra sanning om samma relation.
                switchWatchDao.deleteAll()
                fundDao.deleteAll()

                payload.funds.forEach { fundDao.upsert(FundEntity.fromDomain(it)) }
                payload.transactions.forEach { transactionDao.insert(TransactionEntity.fromDomain(it)) }
                payload.suggestionRecords.forEach { suggestionRecordDao.insert(SuggestionRecordEntity.fromDomain(it)) }
                payload.switchWatches.forEach { watch ->
                    val row = SwitchWatchWithCandidates.fromDomain(watch)
                    // Kandidaterna knyts till det id insättningen faktiskt gav i stället för
                    // till filens: normalt är de identiska, men en kandidat som pekar på fel
                    // bevakning är värre än ett omnumrerat id — den syns inte alls.
                    val watchId = switchWatchDao.insertWatch(row.watch)
                    val candidates = row.candidates.map { it.copy(id = 0, watchId = watchId) }
                    if (candidates.isNotEmpty()) switchWatchDao.insertCandidates(candidates)
                }
                payload.suggestionDismissals.forEach { suggestionDismissalDao.upsert(SuggestionDismissalEntity.fromDomain(it)) }
            }

            payload.riskProfile?.let { preferences.setRiskProfile(it) }
            payload.accountType?.let { preferences.setAccountType(it) }
            preferences.setThemeMode(payload.themeMode)
            // Rensas när filen saknar val: en återställning **ersätter**, den slår inte ihop
            // (SET-6), så ett gammalt val får inte överleva en fil där användaren inte hade något.
            payload.chosenBenchmarkIsin
                ?.let { preferences.setChosenBenchmarkIsin(it) }
                ?: preferences.clearChosenBenchmarkIsin()
            // Tom-plan-markören är den här enhetens skanningsstatus, inte en del av filen: en markör
            // nyare än den återställda planens körning hade dolt en plan som aldrig räknats bort.
            preferences.clearSwitchPlanEmptiedAt()

            RestoreSummary(
                funds = payload.funds.size,
                transactions = payload.transactions.size,
                suggestionRecords = payload.suggestionRecords.size,
                switchWatches = payload.switchWatches.size,
            )
        }
    }
}

/** Room-tabellerna som bär backup-kontraktet — se [LocalBackupRepository.observeChanges]. */
private val CONTRACT_TABLES = arrayOf(
    FundEntity.TABLE_NAME,
    TransactionEntity.TABLE_NAME,
    SuggestionRecordEntity.TABLE_NAME,
    SwitchWatchEntity.TABLE_NAME,
    SwitchWatchCandidateEntity.TABLE_NAME,
    SuggestionDismissalEntity.TABLE_NAME,
)
