package se.partee71.fonder.ui.components

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Instrumenterat test av den delade "Genomförd"-markeringen (SET-5, issue #90) — samma
 * mönster som [RiskBadgeTest]. Vaktar att hela raden (inte bara rutan) är växlaren, så
 * etiketten är klickbar och skärmläsaren får **en** nod med rollen kryssruta.
 */
@RunWith(AndroidJUnit4::class)
class FollowedToggleRowTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun visar_omarkerat_lage() {
        composeRule.setContent { FonderTheme { FollowedToggleRow(followed = false, onFollowedChange = {}) } }

        composeRule.onNodeWithText("Genomförd").assertIsOff()
    }

    @Test
    fun visar_markerat_lage() {
        composeRule.setContent { FonderTheme { FollowedToggleRow(followed = true, onFollowedChange = {}) } }

        composeRule.onNodeWithText("Genomförd").assertIsOn()
    }

    @Test
    fun klick_pa_etiketten_vaxlar_och_anropar_callbacken() {
        var value: Boolean? = null
        composeRule.setContent { FonderTheme { FollowedToggleRow(followed = false, onFollowedChange = { value = it }) } }

        composeRule.onNodeWithText("Genomförd").performClick()

        assertEquals(true, value)
    }
}
