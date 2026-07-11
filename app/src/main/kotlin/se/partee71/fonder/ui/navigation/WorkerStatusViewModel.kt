package se.partee71.fonder.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import javax.inject.Inject

/**
 * Driver bakgrundsindikatorn (`WorkerStatusIcon`, NAV-6, issue #27) i navigeringschromet — ren
 * observation av [FundPriceRefreshScheduler.observeIsRunning], inget eget tillstånd.
 */
@HiltViewModel
class WorkerStatusViewModel @Inject constructor(
    scheduler: FundPriceRefreshScheduler,
) : ViewModel() {

    val isRunning: StateFlow<Boolean> =
        scheduler.observeIsRunning().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false,
        )
}
