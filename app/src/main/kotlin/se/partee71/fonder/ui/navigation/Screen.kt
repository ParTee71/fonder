package se.partee71.fonder.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.ui.graphics.vector.ImageVector
import se.partee71.fonder.R

/**
 * Flikarna i den flytande flikraden (NAV-1, NAV-7), i ordning, med sin nyckel ([TabKey]) – roten i
 * flikens egen bakåtstack ([AppBackStack]). Beslut (NAV-9) är en egen flik, och Facit en skärm i
 * stacken under den ([FacitKey]).
 */
enum class Screen(val key: TabKey, val icon: ImageVector, val labelRes: Int) {
    Oversikt(OversiktKey, Icons.Outlined.Home, R.string.nav_oversikt),
    Innehav(InnehavKey, Icons.Outlined.PieChart, R.string.nav_innehav),
    Beslut(BeslutKey, Icons.Outlined.TaskAlt, R.string.nav_beslut),
    Handelser(HandelserKey, Icons.AutoMirrored.Outlined.List, R.string.nav_handelser);

    companion object {
        val START = Oversikt

        /** Fliken vars rot [key] är, eller null för en skärm djupare i stacken. */
        fun forKey(key: AppKey?): Screen? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Rubrik för en skärm djupare i stacken (flikarnas rubriker bor i [Screen.labelRes]). Null för en
 * flik — den har sin stora rubrik i stället för en topprad.
 */
fun titleResFor(key: AppKey): Int? = when (key) {
    is TabKey -> null
    SettingsKey -> R.string.nav_settings
    is FondDetaljKey -> R.string.fond_title
    FundSearchKey -> R.string.fondsok_title
    TransactionFormKey -> R.string.transaktionsform_title
    ImportHoldingsKey -> R.string.import_title
    ImportOrdersKey -> R.string.import_orders_title
    RiskProfileKey -> R.string.riskprofil_title
    FacitKey -> R.string.facit_title
    BenchmarkPickerKey -> R.string.settings_benchmark_section
    is SwitchWatchKey -> R.string.switch_watch_title
    is SwitchWatchPickerKey -> R.string.switch_watch_add_candidate
}
