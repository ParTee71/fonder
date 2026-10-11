package se.partee71.fonder.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regressionstest för NAV-1/NAV-7/NAV-9 — flikradens fyra flikar, startskärmen och att
 * Inställningar, Sålda fonder och Facit är skärmar i stacken med egen rubrik. Ren datakontroll av [Screen], inget
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
    fun `installningar och salda ar inte flikar men har rubrik`() {
        assertNull(Screen.forRoute(Routes.SETTINGS))
        assertNull(Screen.forRoute(Routes.SOLD))
        assertEquals(se.partee71.fonder.R.string.nav_settings, titleResFor(Routes.SETTINGS))
        assertEquals(se.partee71.fonder.R.string.sold_funds_title, titleResFor(Routes.SOLD))
    }

    @Test
    fun `facit ar en skarm i stacken under Beslut med egen rubrik`() {
        // Sedan 0.61.0 (NAV-9): Beslut är en egen flik och Facit en djupare skärm med bakåtpil.
        assertNull(Screen.forRoute(Routes.FACIT))
        assertEquals(se.partee71.fonder.R.string.facit_title, titleResFor(Routes.FACIT))
        assertEquals(Screen.Beslut, Screen.forRoute("beslut"))
    }
}
