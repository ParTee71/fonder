package se.partee71.fonder.ui.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart

/**
 * "Försök igen" efter ett läsfel (NFR-6): skärmens ViewModel håller en [RetrySignal], läsfelets knapp
 * anropar [retry], och tillståndets källa – med felet fångat ([catchAsListError]/[catchAsDetailError])
 * – läses om från början med [restartOn]. Samma mönster som android-ui:s `asListUiState(errors, retry)`,
 * för Fonders skärmar där listan eller det lästa bara är ett fält i skärmens tillstånd.
 */
class RetrySignal {
    // En buffrad plats: ett tryck medan skärmen visas går aldrig förlorat, och två snabba tryck blir en omläsning.
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Läs om källan. */
    fun retry() {
        requests.tryEmit(Unit)
    }

    internal val signals: Flow<Unit> get() = requests.onStart { emit(Unit) }
}

/**
 * Samlar flödet en gång direkt och på nytt efter varje [RetrySignal.retry]. Står **efter** felfångsten,
 * så att ett läsfel blir tillståndet och nästa försök läser om källan i stället för att flödet är slut.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <S> Flow<S>.restartOn(retry: RetrySignal): Flow<S> = retry.signals.flatMapLatest { this }
