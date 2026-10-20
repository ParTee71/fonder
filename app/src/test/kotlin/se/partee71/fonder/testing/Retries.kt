package se.partee71.fonder.testing

import app.cash.turbine.ReceiveTurbine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import se.partee71.fonder.data.common.DataError
import se.partee71.ui.common.UiError

/**
 * En källa som fallerar med [DataError.Database] tills [failing] släpps – för ViewModel-testerna av
 * "Försök igen" (NFR-6). Felet avgörs när flödet **samlas**, inte när det skapas, så att omläsningen
 * efter `retry()` kan lyckas.
 */
class FailingUntilRetry {
    var failing = true

    fun <T> guard(source: () -> Flow<T>): Flow<T> = flow {
        if (failing) throw DataError.Database
        emitAll(source())
    }
}

/**
 * Väntar ut läsfelet från [source], släpper källan, trycker "Försök igen" ([retry]) och returnerar
 * första tillståndet utan fel och laddning – testet hänger (och fälls av timeouten) om omläsningen
 * aldrig sker.
 */
suspend fun <S> ReceiveTurbine<S>.awaitRecoveryAfterRetry(
    source: FailingUntilRetry,
    retry: () -> Unit,
    loading: (S) -> Boolean,
    error: (S) -> UiError?
): S {
    var state = awaitItem()
    while (loading(state)) state = awaitItem()
    assertEquals(DataError.Database, error(state)?.cause)

    source.failing = false
    retry()
    while (error(state) != null || loading(state)) state = awaitItem()
    return state
}
