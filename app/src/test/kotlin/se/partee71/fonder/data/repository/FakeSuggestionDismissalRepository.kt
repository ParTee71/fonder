package se.partee71.fonder.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import se.partee71.fonder.domain.model.SuggestionDismissal
import se.partee71.fonder.domain.model.SuggestionKind
import java.time.LocalDate

/**
 * In-memory-fake av [SuggestionDismissalRepository] (HEM-14) för läsarna av
 * [se.partee71.fonder.domain.usecase.PendingDecisionsSource]. Speglar tabellens nyckel: en rad per
 * fondpar och sort, och ett nytt avstående skriver över datumet.
 */
class FakeSuggestionDismissalRepository : SuggestionDismissalRepository {

    val dismissals = MutableStateFlow<List<SuggestionDismissal>>(emptyList())

    override fun observeAll(): Flow<List<SuggestionDismissal>> = dismissals

    override suspend fun dismiss(sellIsin: String, buyIsin: String, kind: SuggestionKind, today: LocalDate) {
        dismissals.value = dismissals.value.filterNot { it.matches(sellIsin, buyIsin, kind) } +
            SuggestionDismissal(sellIsin = sellIsin, buyIsin = buyIsin, kind = kind, dismissedAtEpochDay = today.toEpochDay())
    }

    override suspend fun undoDismissal(sellIsin: String, buyIsin: String, kind: SuggestionKind) {
        dismissals.value = dismissals.value.filterNot { it.matches(sellIsin, buyIsin, kind) }
    }

    private fun SuggestionDismissal.matches(sellIsin: String, buyIsin: String, kind: SuggestionKind) =
        this.sellIsin == sellIsin && this.buyIsin == buyIsin && this.kind == kind
}
