package se.partee71.fonder.ui.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import se.partee71.fonder.data.common.AppErrors
import se.partee71.ui.common.DetailUiState
import se.partee71.ui.common.UiError

// Fonders detaljskärmar (Hem, Fonddetalj, Pågående byte) har mer än det lästa i sitt tillstånd –
// körstatus, förslag, engångshändelser – så det lästa är ett DetailUiState-fält i skärmens
// tillstånd i stället för hela tillståndet, samma uppdelning som listskärmarna (ListStates.kt).
// Laddning, fel och "finns inte längre" beter sig ändå likadant överallt (NFR-6).

/** Det lästa, eller `null` medan det laddas eller inte gick att läsa. */
fun <T> DetailUiState<T>.valueOrNull(): T? = (this as? DetailUiState.Content)?.value

/** Felet som visas i stället för innehållet, eller `null`. */
fun DetailUiState<*>.errorOrNull(): UiError? = (this as? DetailUiState.Error)?.error

/**
 * Ett fel i detaljskärmens källa (Room eller en extern källa) blir skärmens tillstånd med
 * [DetailUiState.Error] – via [AppErrors], den enda felmappningen – i stället för en krasch.
 */
fun <S> Flow<S>.catchAsDetailError(onError: (DetailUiState.Error) -> S): Flow<S> = catch { emit(onError(DetailUiState.Error(AppErrors.map(it)))) }
