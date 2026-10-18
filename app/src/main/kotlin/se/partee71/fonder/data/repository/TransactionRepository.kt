package se.partee71.fonder.data.repository

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.partee71.fonder.data.room.AppDatabase
import se.partee71.fonder.data.room.daos.FundDao
import se.partee71.fonder.data.room.daos.FundPriceDao
import se.partee71.fonder.data.room.daos.SuggestionDismissalDao
import se.partee71.fonder.data.room.daos.SuggestionRecordDao
import se.partee71.fonder.data.room.daos.SwitchWatchDao
import se.partee71.fonder.data.room.daos.TransactionDao
import se.partee71.fonder.data.room.entities.FundEntity
import se.partee71.fonder.data.room.entities.TransactionEntity
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Transaction

/** Kontrakt för fonder och deras transaktioner (single source of truth = Room). */
interface TransactionRepository {
    fun observeFunds(): Flow<List<Fund>>
    fun observeTransactions(): Flow<List<Transaction>>
    fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>>

    /**
     * Sparar fonden. [Fund.isin] och [Fund.fondlistaFundId] **bevaras** från den lagrade raden
     * när det inkommande värdet är null — de fylls i över tid (användarens egen inmatning i
     * Fonddetalj, NAV-2, eller ett maskinellt uppslag) medan flera anropare bara har den
     * magra katalog-/sökträffen i handen. Utan sammanslagningen raderade ett andra "Lägg till"
     * från Fondsök ett bekräftat ISIN och därmed kurskedjan för fonden. Samma princip som
     * [se.partee71.fonder.data.repository.FundMetadataRepository]s `toEntityPreservingDerivedState`.
     */
    suspend fun upsertFund(fund: Fund)

    /**
     * Skriver raden — **bara** raden. Skärmar och importer ska inte anropa den här direkt utan gå
     * via [se.partee71.fonder.domain.usecase.RecordTransaction] (BYT-2), som också driver bytets
     * livscykel; en transaktion som skrivs här utan den vägen lämnar ett pågående byte okopplat.
     */
    suspend fun addTransaction(tx: Transaction): Long

    /**
     * Tar bort transaktionen (TRX-2). En bevakning som pekar på den får sina länkar nollade men
     * ligger kvar — användardata raderas aldrig som sidoeffekt.
     */
    suspend fun deleteTransaction(id: Long)

    /**
     * Tömmer all bevakad data (fonder, transaktioner, cachade kurser och inspelade
     * bytesförslag med användarens avståenden) — irreversibelt, se SET-1. `suggestion_records` ingår eftersom raderna är
     * genuin, ej rekonstruerbar användardata (samma skäl som de ingår i backup-kontraktet):
     * lämnades de kvar visade Hem efter en tömning fortfarande "Sälj X → Köp Y" för en
     * portfölj som inte längre finns, och dygnsdedupen spärrade en nyberäknad plan.
     *
     * `fund_metadata` och `fx_rates` rensas medvetet **inte** — ren cache som hämtas om vid
     * behov (se `FundMetadataEntity`s KDoc), utan koppling till vad användaren har bevakat.
     */
    suspend fun clearAll()
}

@Singleton
class RoomTransactionRepository @Inject constructor(
    private val database: AppDatabase,
    private val fundDao: FundDao,
    private val transactionDao: TransactionDao,
    private val fundPriceDao: FundPriceDao,
    private val suggestionRecordDao: SuggestionRecordDao,
    private val switchWatchDao: SwitchWatchDao,
    private val suggestionDismissalDao: SuggestionDismissalDao
) : TransactionRepository {

    override fun observeFunds(): Flow<List<Fund>> = fundDao.observeAll().map { list -> list.map(FundEntity::toDomain) }

    override fun observeTransactions(): Flow<List<Transaction>> = transactionDao.observeAll().map { list -> list.map(TransactionEntity::toDomain) }

    override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = transactionDao.observeForFund(fundId).map { list -> list.map(TransactionEntity::toDomain) }

    override suspend fun upsertFund(fund: Fund) {
        val stored = fundDao.getByFundId(fund.fundId)
        fundDao.upsert(
            FundEntity.fromDomain(
                fund.copy(
                    isin = fund.isin ?: stored?.isin,
                    fondlistaFundId = fund.fondlistaFundId ?: stored?.fondlistaFundId
                )
            )
        )
    }

    override suspend fun addTransaction(tx: Transaction): Long = transactionDao.insert(TransactionEntity.fromDomain(tx))

    override suspend fun deleteTransaction(id: Long) {
        database.withTransaction {
            switchWatchDao.clearTransactionLinks(id)
            transactionDao.deleteById(id)
        }
    }

    override suspend fun clearAll() {
        database.withTransaction {
            transactionDao.deleteAll()
            fundPriceDao.deleteAll()
            suggestionRecordDao.deleteAll()
            // Avståendena (HEM-14) gäller en portfölj som inte längre finns.
            suggestionDismissalDao.deleteAll()
            // Bevakade byten är användardata i samma kategori som förslagen (NFR-1) och töms
            // med dem (SET-1) — kandidaterna följer med via ON DELETE CASCADE.
            switchWatchDao.deleteAll()
            fundDao.deleteAll()
        }
    }
}
