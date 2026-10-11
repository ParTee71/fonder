package se.partee71.fonder.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.ui.graphics.vector.ImageVector
import se.partee71.fonder.R

/**
 * Flikarna i den flytande flikraden (NAV-1, NAV-7), i ordning. Översikt, Innehav och Händelser har
 * samma rutter som före flikbytet; Beslut (NAV-9) är en egen skärm, och Facit en skärm i stacken
 * under den ([Routes.FACIT]).
 */
enum class Screen(val route: String, val icon: ImageVector, val labelRes: Int) {
    Oversikt("hem", Icons.Outlined.Home, R.string.nav_oversikt),
    Innehav("portfolj", Icons.Outlined.PieChart, R.string.nav_innehav),
    Beslut("beslut", Icons.Outlined.TaskAlt, R.string.nav_beslut),
    Handelser("transaktioner", Icons.AutoMirrored.Outlined.List, R.string.nav_handelser);

    companion object {
        val START = Oversikt

        /** Fliken för en rutt, eller null för en skärm djupare i stacken. */
        fun forRoute(route: String?): Screen? = entries.firstOrNull { it.route == route }
    }
}

/**
 * Rubrik för en skärm djupare i stacken (flikarnas rubriker bor i [Screen.labelRes]). Null för en
 * okänd rutt — då visas ingen topprad alls, i stället för en tom.
 */
fun titleResFor(route: String?): Int? = when (route) {
    Routes.SETTINGS -> R.string.nav_settings
    Routes.FOND -> R.string.fond_title
    Routes.FUND_SEARCH -> R.string.fondsok_title
    Routes.TRANSACTION_FORM -> R.string.transaktionsform_title
    Routes.IMPORT_HOLDINGS -> R.string.import_title
    Routes.IMPORT_ORDERS -> R.string.import_orders_title
    Routes.RISK_PROFILE -> R.string.riskprofil_title
    Routes.FACIT -> R.string.facit_title
    Routes.BENCHMARK_PICKER -> R.string.settings_benchmark_section
    Routes.SWITCH_WATCH -> R.string.switch_watch_title
    Routes.SWITCH_WATCH_PICKER -> R.string.switch_watch_add_candidate
    else -> null
}

object Routes {
    /** Inställningar — sedan NAV-7 en skärm bakom ikonen på Översikt, inte en flik. */
    const val SETTINGS = "settings"

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

    /** Fondsök i valläge för jämförelsefonden (HEM-10). */
    const val BENCHMARK_PICKER = "benchmark_picker"

    /** Facit — utfallet av bytesplanens inspelade förslag (SET-5); en skärm i stacken från Beslut och Inställningar (NAV-9). */
    const val FACIT = "facit"

    /** Pågående byte — perioden mellan sälj och köp med de bevakade alternativen (ANA-12, issue #114). */
    const val SWITCH_WATCH = "switch-watch/{watchId}"
    fun switchWatch(watchId: Long) = "switch-watch/$watchId"

    /**
     * Fondsök i valläge för att lägga till ett eget alternativ i en bevakning (ANA-13) — samma
     * mönster som [BENCHMARK_PICKER], men rutten bär bevakningens id så den valda fonden hamnar
     * i rätt bevakning även om skärmen återskapas.
     */
    const val SWITCH_WATCH_PICKER = "switch-watch/{watchId}/lagg-till"
    fun switchWatchPicker(watchId: Long) = "switch-watch/$watchId/lagg-till"
}
