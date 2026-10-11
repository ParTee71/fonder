package se.partee71.fonder.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regressionstest för NAV-1/NAV-7 — flikradens fyra flikar, startskärmen och att Inställningar
 * och Sålda fonder blivit skärmar i stacken med egen rubrik. Ren datakontroll av [Screen], inget
 * Compose/Hilt behövs för det.
 */
class ScreenTest {

    @Test
    fun `flikraden har fyra flikar i ratt ordning`() {
        assertEquals(
            listOf("hem", "portfolj", "facit", "transaktioner"),
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
    fun `facit ar fliken Beslut`() {
        assertEquals(Screen.Beslut, Screen.forRoute(Routes.FACIT))
        assertNull(titleResFor(Routes.FACIT))
    }
}
