package se.partee71.fonder.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regressionstest för NAV-1 (issue #14) — bottennavigeringens 5-flikstruktur och
 * startskärmen. Ren datakontroll av [Screen], inget Compose/Hilt behövs för det.
 */
class ScreenTest {

    @Test
    fun `bottennavigeringen har fem flikar i ratt ordning`() {
        assertEquals(
            listOf("hem", "portfolj", "transaktioner", "salda", "settings"),
            Screen.entries.map { it.route },
        )
    }

    @Test
    fun `hem ar ny startskarm`() {
        assertEquals(Screen.Hem, Screen.START)
    }
}
