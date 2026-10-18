package se.partee71.fonder.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import se.partee71.fonder.domain.usecase.PendingDecisions
import se.partee71.fonder.domain.usecase.PendingDecisionsSource

/**
 * Driver kopparmärket på fliken Beslut (NAV-9) — [PendingDecisions.count] ur samma källa som
 * fliken själv och Översiktens "Att besluta" (HEM-13), så de tre aldrig visar olika antal.
 */
@HiltViewModel
class DecisionBadgeViewModel @Inject constructor(
    pendingDecisionsSource: PendingDecisionsSource
) : ViewModel() {

    val count: StateFlow<Int> =
        pendingDecisionsSource.observe().map { it.count }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = 0
        )
}
