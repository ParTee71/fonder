package se.partee71.fonder.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regressionstest för NAV-1/NAV-7/NAV-9 — flikradens fyra flikar, startskärmen och att
 * Inställningar och Facit är skärmar i stacken med egen rubrik. Ren datakontroll av [Screen], inget
 * Compose/Hilt behövs för det.
 */
class ScreenTest {

    @Test
    fun `flikraden har fyra flikar i ratt ordning`() {
        assertEquals(
            listOf("hem", "portfolj", "beslut", "transaktioner"),
            Screen.entries.map { it.route },
        )
    }

    @Test
    fun `oversikt ar startskarm`() {
        assertEquals(Screen.Oversikt, Screen.START)
    }

    @Test
    fun `installningar ar inte en flik men har rubrik, och salda fonder finns inte langre som skarm`() {
        assertNull(Screen.forRoute(Routes.SETTINGS))
        assertEquals(se.partee71.fonder.R.string.nav_settings, titleResFor(Routes.SETTINGS))
        // Sedan 0.62.0 (SLD-1, NAV-5) står säljen i Händelsers lista – rutten "salda" är borttagen.
        assertNull(Screen.forRoute("salda"))
        assertNull(titleResFor("salda"))
    }

    @Test
    fun `facit ar en skarm i stacken under Beslut med egen rubrik`() {
        // Sedan 0.61.0 (NAV-9): Beslut är en egen flik och Facit en djupare skärm med bakåtpil.
        assertNull(Screen.forRoute(Routes.FACIT))
        assertEquals(se.partee71.fonder.R.string.facit_title, titleResFor(Routes.FACIT))
        assertEquals(Screen.Beslut, Screen.forRoute("beslut"))
    }
}
