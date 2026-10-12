package se.partee71.fonder.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/**
 * Alla skärmar i appen (NAV-7). Sparas med kotlinx.serialization i [AppBackStack], så att
 * flikarnas stackar överlever rotation och processdöd. En skärm med argument bär dem i nyckeln
 * (t.ex. [FondDetaljKey.fundId]) och skickar dem till sin ViewModel med assisted injection.
 */
@Serializable
sealed interface AppKey : NavKey

/** En flik i den flytande flikraden – roten i en egen bakåtstack ([Screen]). */
@Serializable
sealed interface TabKey : AppKey

/** Översikt – startfliken (NAV-1). */
@Serializable
data object OversiktKey : TabKey

/** Innehav (NAV-1). */
@Serializable
data object InnehavKey : TabKey

/** Beslut (NAV-9). */
@Serializable
data object BeslutKey : TabKey

/** Händelser (NAV-1, NAV-5). */
@Serializable
data object HandelserKey : TabKey

/** Inställningar — en skärm bakom ikonen på Översikt, inte en flik (NAV-7). */
@Serializable
data object SettingsKey : AppKey

/** Fonddetalj för [fundId] (Handelsbankens fondlista-id, TP-9). */
@Serializable
data class FondDetaljKey(val fundId: String) : AppKey

/** Sök och lägg till en Handelsbanken-fond i bevakningen. */
@Serializable
data object FundSearchKey : AppKey

/** Registrera en ny fondtransaktion (köp/sälj). */
@Serializable
data object TransactionFormKey : AppKey

/** Importera befintliga innehav från en Handelsbanken-Excel-export. */
@Serializable
data object ImportHoldingsKey : AppKey

/** Importera exakta transaktioner från Handelsbanken-PDF-avräkningsnotor. */
@Serializable
data object ImportOrdersKey : AppKey

/** Riskprofil — engångsenkät och målrisknivå, från Beslut och Inställningar (SET-3). */
@Serializable
data object RiskProfileKey : AppKey

/** Fondsök i valläge för jämförelsefonden (HEM-10). */
@Serializable
data object BenchmarkPickerKey : AppKey

/**
 * Facit — utfallet av bytesplanens inspelade förslag (SET-5); en skärm i stacken från Beslut och
 * Inställningar (NAV-9).
 */
@Serializable
data object FacitKey : AppKey

/** Pågående byte [watchId] — perioden mellan sälj och köp med de bevakade alternativen (ANA-12). */
@Serializable
data class SwitchWatchKey(val watchId: Long) : AppKey

/**
 * Fondsök i valläge för att lägga till ett eget alternativ i bevakningen [watchId] (ANA-13) — samma
 * mönster som [BenchmarkPickerKey], men nyckeln bär bevakningens id så den valda fonden hamnar i
 * rätt bevakning även om skärmen återskapas.
 */
@Serializable
data class SwitchWatchPickerKey(val watchId: Long) : AppKey
