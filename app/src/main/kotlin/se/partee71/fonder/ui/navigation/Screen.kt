package se.partee71.fonder.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.ui.graphics.vector.ImageVector

/** Toppnivåskärmar som visas i navigeringsraden. */
enum class Screen(val route: String, val icon: ImageVector, val labelRes: Int) {
    Hem("hem", Icons.Outlined.Home, se.partee71.fonder.R.string.nav_hem),
    Portfolj("portfolj", Icons.Outlined.PieChart, se.partee71.fonder.R.string.nav_portfolj),
    Transaktioner("transaktioner", Icons.Outlined.SwapVert, se.partee71.fonder.R.string.nav_transaktioner),
    Salda("salda", Icons.Outlined.Sell, se.partee71.fonder.R.string.nav_salda),
    Settings("settings", Icons.Outlined.Settings, se.partee71.fonder.R.string.nav_settings);

    companion object {
        val START = Hem
    }
}

/**
 * Rubrik för en icke-toppnivårutt (toppnivåernas titlar bor i [Screen.labelRes]). Null för en
 * okänd rutt — då visas ingen `TopAppBar` alls, i stället för en tom.
 */
fun titleResFor(route: String?): Int? = when (route) {
    Routes.FOND -> se.partee71.fonder.R.string.fond_title
    Routes.FUND_SEARCH -> se.partee71.fonder.R.string.fondsok_title
    Routes.TRANSACTION_FORM -> se.partee71.fonder.R.string.transaktionsform_title
    Routes.IMPORT_HOLDINGS -> se.partee71.fonder.R.string.import_title
    Routes.IMPORT_ORDERS -> se.partee71.fonder.R.string.import_orders_title
    Routes.RISK_PROFILE -> se.partee71.fonder.R.string.riskprofil_title
    Routes.FACIT -> se.partee71.fonder.R.string.facit_title
    else -> null
}

object Routes {
    /** Fonddetalj för ett givet fundId (Handelsbankens fondlista-id, se issue #2/#3). */
    const val FOND = "fond/{fundId}"
    fun fond(fundId: String) = "fond/$fundId"

    /** Sök och lägg till en Handelsbanken-fond i bevakningen. */
    const val FUND_SEARCH = "fund-search"

    /** Registrera en ny fondtransaktion (köp/sälj), se issue #4. */
    const val TRANSACTION_FORM = "transaction-form"

    /** Importera befintliga innehav från en Handelsbanken-Excel-export, se issue #8. */
    const val IMPORT_HOLDINGS = "import-holdings"

    /** Importera exakta transaktioner från Handelsbanken-PDF-avräkningsnotor, se issue #8-uppföljning. */
    const val IMPORT_ORDERS = "import-orders"

    /** Riskprofil — engångsenkät + målrisknivå, nås från Inställningar (SET-3, issue #68). */
    const val RISK_PROFILE = "risk-profile"

    /** Facit — utfallet av bytesplanens inspelade förslag, nås från Inställningar (SET-5, issue #80). */
    const val FACIT = "facit"
}
