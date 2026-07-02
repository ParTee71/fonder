package se.partee71.fonder.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.ui.graphics.vector.ImageVector

/** Toppnivåskärmar som visas i navigeringsraden. */
enum class Screen(val route: String, val icon: ImageVector, val labelRes: Int) {
    Portfolj("portfolj", Icons.Outlined.PieChart, se.partee71.fonder.R.string.nav_portfolj),
    Transaktioner("transaktioner", Icons.Outlined.SwapVert, se.partee71.fonder.R.string.nav_transaktioner),
    Settings("settings", Icons.Outlined.Settings, se.partee71.fonder.R.string.nav_settings);

    companion object {
        val START = Portfolj
    }
}

object Routes {
    /** Fonddetalj för ett givet fundId (Handelsbankens fondlista-id, se issue #2/#3). */
    const val FOND = "fond/{fundId}"
    fun fond(fundId: String) = "fond/$fundId"

    /** Sök och lägg till en Handelsbanken-fond i bevakningen. */
    const val FUND_SEARCH = "fund-search"
}
