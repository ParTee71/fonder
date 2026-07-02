package se.partee71.fonder.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import se.partee71.fonder.data.room.daos.FundDao
import se.partee71.fonder.data.room.daos.TransactionDao
import se.partee71.fonder.data.room.entities.FundEntity
import se.partee71.fonder.data.room.entities.TransactionEntity
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Transaction
import javax.inject.Inject
import javax.inject.Singleton

/** Kontrakt för fonder och deras transaktioner (single source of truth = Room). */
interface TransactionRepository {
    fun observeFunds(): Flow<List<Fund>>
    fun observeTransactions(): Flow<List<Transaction>>
    fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>>
    suspend fun upsertFund(fund: Fund)
    suspend fun addTransaction(tx: Transaction): Long
    suspend fun deleteTransaction(id: Long)
}

@Singleton
class RoomTransactionRepository @Inject constructor(
    private val fundDao: FundDao,
    private val transactionDao: TransactionDao,
) : TransactionRepository {

    override fun observeFunds(): Flow<List<Fund>> =
        fundDao.observeAll().map { list -> list.map(FundEntity::toDomain) }

    override fun observeTransactions(): Flow<List<Transaction>> =
        transactionDao.observeAll().map { list -> list.map(TransactionEntity::toDomain) }

    override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> =
        transactionDao.observeForFund(fundId).map { list -> list.map(TransactionEntity::toDomain) }

    override suspend fun upsertFund(fund: Fund) =
        fundDao.upsert(FundEntity.fromDomain(fund))

    override suspend fun addTransaction(tx: Transaction): Long =
        transactionDao.insert(TransactionEntity.fromDomain(tx))

    override suspend fun deleteTransaction(id: Long) =
        transactionDao.deleteById(id)
}
