package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** Adresserar behållaren för svepgesten i instrumenttesterna (UI-11). */
const val PULL_TO_REFRESH_TEST_TAG = "pull_to_refresh"

/**
 * Dra ned för att uppdatera (UI-11) — den delade behållaren varje skärm som visar **hämtad**
 * data lägger sitt innehåll i (regel 4). Skärmarna rör aldrig Material3:s `PullToRefreshBox`
 * direkt, av samma skäl som ingen rör Vicos API utanför [se.partee71.fonder.ui.diagram.FundLineChart]
 * (TP-12): ett byte av gest-API:t ska vara en ändring på ett ställe, inte sex.
 *
 * [refreshing] ska drivas av **samma** körstatus som kortens väntesnurror (NAV-6,
 * `BackgroundWork`), aldrig av en egen lokal flagga: en lokal flagga slutar antingen snurra
 * innan datan landat eller fortsätter efter att jobbet tagit slut, och indikatorn skulle då
 * beskriva något annat än vad appen faktiskt gör.
 *
 * Skärmar utan hämtad data (Transaktioner, Sålda fonder, Riskprofil, Inställningar,
 * importflödena) använder den **inte** — en gest som inte gör något är värre än ingen gest.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PullToRefreshContainer(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize().testTag(PULL_TO_REFRESH_TEST_TAG),
    ) {
        content()
    }
}
