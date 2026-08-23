package se.partee71.fonder.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.first
import se.partee71.fonder.data.datastore.PreferencesRepository
import se.partee71.fonder.data.room.AppDatabase
import se.partee71.fonder.data.room.daos.FundDao
import se.partee71.fonder.data.room.daos.SuggestionRecordDao
import se.partee71.fonder.data.room.daos.SwitchWatchDao
import se.partee71.fonder.data.room.daos.TransactionDao
import se.partee71.fonder.data.room.entities.FundEntity
import se.partee71.fonder.data.room.entities.SuggestionRecordEntity
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
    private val preferences: PreferencesRepository,
) : BackupRepository {

    override suspend fun export(): Result<String> = runCatching {
        BackupSerializer.encode(
            BackupPayload(
                exportedAtEpochMillis = System.currentTimeMillis(),
                funds = fundDao.getAll().map(FundEntity::toDomain),
                transactions = transactionDao.getAll().map(TransactionEntity::toDomain),
                suggestionRecords = suggestionRecordDao.getAll().map(SuggestionRecordEntity::toDomain),
                switchWatches = switchWatchDao.getAll().map(SwitchWatchWithCandidates::toDomain),
                riskProfile = preferences.riskProfile.first(),
                accountType = preferences.accountType.first(),
                themeMode = preferences.themeMode.first(),
                chosenBenchmarkIsin = preferences.chosenBenchmarkIsin.first(),
            ),
        )
    }

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
            }

            payload.riskProfile?.let { preferences.setRiskProfile(it) }
            payload.accountType?.let { preferences.setAccountType(it) }
            preferences.setThemeMode(payload.themeMode)
            // Rensas när filen saknar val: en återställning **ersätter**, den slår inte ihop
            // (SET-6), så ett gammalt val får inte överleva en fil där användaren inte hade något.
            payload.chosenBenchmarkIsin
                ?.let { preferences.setChosenBenchmarkIsin(it) }
                ?: preferences.clearChosenBenchmarkIsin()

            RestoreSummary(
                funds = payload.funds.size,
                transactions = payload.transactions.size,
                suggestionRecords = payload.suggestionRecords.size,
                switchWatches = payload.switchWatches.size,
            )
        }
    }
}
