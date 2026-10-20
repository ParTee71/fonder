package se.partee71.fonder.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.domain.usecase.SwitchPlanResolver

/**
 * Alla skärmar i appen (NAV-7). Sparas med kotlinx.serialization i [AppBackStack], så att
 * flikarnas stackar överlever rotation och processdöd. En skärm med argument bär dem i nyckeln
 * (t.ex. [FondDetaljKey.fundId]) och skickar dem till sin ViewModel med assisted injection.
 */
@Serializable
sealed interface AppKey : NavKey

/**
 * En skärm byggd på en av android-ui:s skärmramar (`EntityListScreen`, `EntityEditScreen`,
 * `EntityDetailScreen`) eller annars med egen `Scaffold` och bibliotekets `AppTopBar`. Skalet
 * ([AppNavigationShell]) ritar då ingen egen rubrik ([titleResFor] är `null`) och lägger ingen
 * `imePadding` – ramen gör båda själv; chromets bakgrundsindikator (NAV-6) får ramens topprad via
 * `LocalTopBarStatus`. Kan kombineras med [TabKey] när en flik går över till en ram.
 */
@Serializable
sealed interface FrameKey : AppKey

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

/**
 * Registrera en ny fondtransaktion (köp/sälj, NAV-4). Utan argument det tomma formuläret från
 * plusmenyn; med argument förifyllt (BYT-2, issue #144):
 *
 * - **Följ** på Beslut och Fonddetalj: säljet för förslaget [suggestionRecordId] — fond och typ
 *   låsta, andelar ur [prefillAmountKr] (eller förslagets eget belopp) och senaste kursen.
 * - **"Köpte den här"** på Pågående byte: köpet som avslutar bevakningen [watchId].
 *
 * Fonden pekas ut med [fundId] när den är känd, annars med [fundIsin] — en kandidat i ett byte är
 * sällan en fond appen redan bevakar och slås då upp på ISIN; fondväljaren är reserven när
 * uppslaget inte ger något.
 */
@Serializable
data class TransactionFormKey(
    val fundId: String? = null,
    val fundIsin: String? = null,
    val type: TransactionType? = null,
    val suggestionRecordId: Long? = null,
    val watchId: Long? = null,
    val prefillAmountKr: Double? = null
) : AppKey {
    /** Förifyllt från ett förslag eller en bevakning — formuläret registrerar då en redan gjord affär. */
    val prefilled: Boolean get() = type != null && (fundId != null || fundIsin != null)

    companion object {
        /** **Följ** på ett förslag (Beslut, Fonddetalj, HEM-14/ANA-10): säljet av säljfonden för förslaget. */
        fun follow(suggestion: SwitchPlanResolver.Suggestion) = TransactionFormKey(
            fundIsin = suggestion.sellIsin,
            type = TransactionType.SALJ,
            suggestionRecordId = suggestion.recordId,
            prefillAmountKr = suggestion.switchValueKr
        )

        /** **Följ** på ett inspelat billigare alternativ i Fonddetalj (ANA-9/ANA-10): säljet av fonden [fundId]; beloppet tas ur förslaget. */
        fun follow(fundId: String, recordId: Long) = TransactionFormKey(fundId = fundId, type = TransactionType.SALJ, suggestionRecordId = recordId)

        /** **"Köpte den här"** på Pågående byte (ANA-12): köpet av kandidaten [isin] som avslutar bevakningen [watchId]. */
        fun bought(watchId: Long, isin: String) = TransactionFormKey(fundIsin = isin, type = TransactionType.KOP, watchId = watchId)
    }
}

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

/**
 * Komponentgalleriet – alla delade komponenter med påhittad data. Nås från Inställningar, men bara
 * i debug-bygget (skill shared-ui-components); en ramskärm med egen topprad ([FrameKey]).
 */
@Serializable
data object ComponentGalleryKey : FrameKey
