package se.partee71.fonder.ui.common

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import se.partee71.fonder.data.common.AppErrors
import se.partee71.ui.common.ListUiState
import se.partee71.ui.common.UiError

// Fonders listskärmar (Händelser, Beslut, Facit, Fondsök) har mer än listan i sitt tillstånd –
// filter, summeringar, körstatus – så listan är ett ListUiState-fält i skärmens tillstånd i stället
// för hela tillståndet. Laddning, tomt och fel beter sig ändå likadant överallt (NFR-6).

/** Listans rader, eller inga medan den laddas, är tom eller inte gick att läsa. */
fun <T> ListUiState<T>.itemsOrEmpty(): ImmutableList<T> = (this as? ListUiState.Content)?.items ?: persistentListOf()

/** Felet som visas i stället för listan, eller `null`. */
fun ListUiState<*>.errorOrNull(): UiError? = (this as? ListUiState.Error)?.error

/**
 * Ett fel i listskärmens källa (Room eller en extern källa) blir skärmens tillstånd med
 * [ListUiState.Error] – via [AppErrors], den enda felmappningen – i stället för en krasch.
 */
fun <S> Flow<S>.catchAsListError(onError: (ListUiState.Error) -> S): Flow<S> = catch { emit(onError(ListUiState.Error(AppErrors.map(it)))) }
