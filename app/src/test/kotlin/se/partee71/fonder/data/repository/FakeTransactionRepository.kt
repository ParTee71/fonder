package se.partee71.fonder.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.Transaction

/**
 * In-memory-fake av [TransactionRepository] för läsare av de bevakade fonderna — som
 * [se.partee71.fonder.domain.usecase.FacitSummary], som slår upp säljfondens fundId ur [funds].
 * Den delade fake:n — nya tester ska använda den i stället för att bära en egen inbäddad.
 */
class FakeTransactionRepository : TransactionRepository {

    val funds = MutableStateFlow<List<Fund>>(emptyList())
    val transactions = MutableStateFlow<List<Transaction>>(emptyList())

    /** Antal gånger fondlistan begärts — en import ska läsa den en gång, inte per rad. */
    var fundsReads = 0

    override fun observeFunds(): Flow<List<Fund>> {
        fundsReads++
        return funds
    }

    override fun observeTransactions(): Flow<List<Transaction>> = transactions

    override fun observeTransactionsForFund(fundId: String): Flow<List<Transaction>> = transactions.map { all -> all.filter { it.fundId == fundId } }

    override suspend fun upsertFund(fund: Fund) {
        funds.value = funds.value.filterNot { it.fundId == fund.fundId } + fund
    }

    override suspend fun addTransaction(tx: Transaction): Long {
        val id = (transactions.value.maxOfOrNull { it.id } ?: 0L) + 1
        transactions.value = transactions.value + tx.copy(id = id)
        return id
    }

    override suspend fun deleteTransaction(id: Long) {
        transactions.value = transactions.value.filterNot { it.id == id }
    }

    override suspend fun clearAll() {
        funds.value = emptyList()
        transactions.value = emptyList()
    }
}
