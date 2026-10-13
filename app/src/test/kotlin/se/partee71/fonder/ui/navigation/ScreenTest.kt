package se.partee71.fonder.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.partee71.fonder.R

/**
 * Regressionstest för NAV-1/NAV-7/NAV-9 — flikradens fyra flikar, startskärmen och att
 * Inställningar och Facit är skärmar i stacken med egen rubrik. Ren datakontroll av [Screen] och
 * [titleResFor], inget Compose/Hilt behövs för det.
 */
class ScreenTest {

    @Test
    fun `flikraden har fyra flikar i ratt ordning`() {
        assertEquals(
            listOf(OversiktKey, InnehavKey, BeslutKey, HandelserKey),
            Screen.entries.map { it.key },
        )
    }

    @Test
    fun `oversikt ar startskarm`() {
        assertEquals(Screen.Oversikt, Screen.START)
        assertEquals(listOf<AppKey>(OversiktKey), AppBackStack().entries)
    }

    @Test
    fun `installningar ar inte en flik men har rubrik, och flikarna har ingen topprad`() {
        assertNull(Screen.forKey(SettingsKey))
        assertEquals(R.string.nav_settings, titleResFor(SettingsKey))
        Screen.entries.forEach { assertNull(titleResFor(it.key)) }
    }

    @Test
    fun `facit ar en skarm i stacken under Beslut med egen rubrik`() {
        // Sedan 0.61.0 (NAV-9): Beslut är en egen flik och Facit en djupare skärm med bakåtpil.
        assertNull(Screen.forKey(FacitKey))
        assertEquals(R.string.facit_title, titleResFor(FacitKey))
        assertEquals(Screen.Beslut, Screen.forKey(BeslutKey))
    }

    @Test
    fun `skarmar med argument har rubrik oavsett argument`() {
        assertEquals(R.string.fond_title, titleResFor(FondDetaljKey("SHB0000442")))
        assertEquals(R.string.switch_watch_title, titleResFor(SwitchWatchKey(7)))
        assertEquals(R.string.switch_watch_add_candidate, titleResFor(SwitchWatchPickerKey(7)))
    }

    @Test
    fun `transaktionsformularet har rubrik efter laget`() {
        // NAV-4/BYT-2: tomt formulär, "Registrera säljet" från Följ, "Registrera köpet" från "Köpte den här".
        assertEquals(R.string.transaktionsform_title, titleResFor(TransactionFormKey()))
        assertEquals(R.string.transaktionsform_title_sale, titleResFor(TransactionFormKey(fundIsin = "SE_A", type = se.partee71.fonder.domain.model.TransactionType.SALJ)))
        assertEquals(R.string.transaktionsform_title_buy, titleResFor(TransactionFormKey.bought(7, "SE_A")))
    }
}
