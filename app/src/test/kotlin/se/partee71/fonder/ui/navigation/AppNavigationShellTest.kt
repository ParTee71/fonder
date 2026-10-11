package se.partee71.fonder.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.components.APP_FLOATING_TOOLBAR_TAG
import se.partee71.fonder.ui.theme.FonderTheme

/**
 * Navigeringsskalet (NAV-7, NAV-8, UI-8) i JVM med Robolectric – riktiga flikrad, plusmeny och
 * toppradsval, men enkla ersättare för skärmarna så att testet inte behöver Hilt: fyra flikar med
 * Översikt som start, plusmenyn på varje flik som öppnar sina skärmar, Inställningar bakom ikonen på
 * Översikt, ingen länk till Sålda fonder på Händelser (SLD-1), flikraden och plusmenyn borta på en djupare skärm, och
 * bakåt från en annan flik till Översikt.
 */
@RunWith(RobolectricTestRunner::class)
class AppNavigationShellTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val stubRoutes = Screen.entries.map { it.route } + listOf(
        Routes.SETTINGS,
        Routes.FACIT,
        Routes.TRANSACTION_FORM,
        Routes.IMPORT_ORDERS,
        Routes.IMPORT_HOLDINGS,
        Routes.FUND_SEARCH,
    )

    private lateinit var navController: NavHostController

    private fun setShell(beslutBadgeCount: Int = 0) = composeRule.setContent {
        FonderTheme {
            navController = rememberNavController()
            AppNavigationShell(isWorkerRunning = false, beslutBadgeCount = beslutBadgeCount, navController = navController) {
                stubRoutes.forEach { route -> composable(route) { Text("skärm:$route") } }
            }
        }
    }

    private fun assertOnScreen(route: String) = composeRule.onNodeWithText("skärm:$route").assertIsDisplayed()

    private fun assertTabChrome(visible: Boolean) {
        if (visible) {
            composeRule.onNodeWithTag(APP_FLOATING_TOOLBAR_TAG).assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Lägg till").assertIsDisplayed()
        } else {
            composeRule.onNodeWithTag(APP_FLOATING_TOOLBAR_TAG).assertDoesNotExist()
            composeRule.onNodeWithContentDescription("Lägg till").assertDoesNotExist()
        }
    }

    @Test
    fun `fyra flikar med Översikt som start och plusmenyn på varje flik`() {
        setShell()

        assertOnScreen("hem")
        composeRule.onNodeWithContentDescription("Översikt").assertIsSelected()
        listOf(
            "Innehav" to "portfolj",
            "Beslut" to "beslut",
            "Händelser" to "transaktioner",
            "Översikt" to "hem",
        ).forEach { (label, route) ->
            composeRule.onNodeWithContentDescription(label).performClick()
            assertOnScreen(route)
            composeRule.onNodeWithContentDescription(label).assertIsSelected()
            // Flikens rubrik i innehållet, ingen bakåtpil.
            composeRule.onNodeWithText(label).assertIsDisplayed()
            composeRule.onNodeWithContentDescription("Tillbaka").assertDoesNotExist()
            assertTabChrome(visible = true)
        }
    }

    @Test
    fun `plusmenyn öppnar sina skärmar och döljs med flikraden på djupare skärmar`() {
        setShell()

        listOf(
            "Ny transaktion" to Routes.TRANSACTION_FORM,
            "Importera avräkningsnota" to Routes.IMPORT_ORDERS,
            "Importera innehav" to Routes.IMPORT_HOLDINGS,
            "Bevaka ny fond" to Routes.FUND_SEARCH,
        ).forEach { (label, route) ->
            composeRule.onNodeWithContentDescription("Lägg till").performClick()
            composeRule.onNodeWithText(label).performClick()

            assertOnScreen(route)
            assertTabChrome(visible = false)
            composeRule.onNodeWithContentDescription("Tillbaka").performClick()
            assertOnScreen("hem")
            assertTabChrome(visible = true)
        }
    }

    @Test
    fun `inställningsikonen på Översikt öppnar Inställningar med bakåtpil`() {
        setShell()

        composeRule.onNodeWithContentDescription("Inställningar").performClick()

        assertOnScreen(Routes.SETTINGS)
        composeRule.onNodeWithText("Inställningar").assertIsDisplayed()
        assertTabChrome(visible = false)
        composeRule.onNodeWithContentDescription("Tillbaka").performClick()
        assertOnScreen("hem")
    }

    @Test
    fun `Händelser har ingen länk till Sålda fonder – säljen står i listan`() {
        // Sedan 0.62.0 (SLD-1, NAV-5): Sålda fonder är en del av Händelser, inte en egen skärm.
        setShell()

        composeRule.onNodeWithContentDescription("Händelser").performClick()

        assertOnScreen("transaktioner")
        assertTabChrome(visible = true)
        composeRule.onNodeWithText("Sålda fonder").assertDoesNotExist()
    }

    @Test
    fun `Beslut bär kopparmärket med antalet och läser upp det`() {
        setShell(beslutBadgeCount = 3)

        composeRule.onNodeWithContentDescription("Beslut, 3 nya").assertIsDisplayed()
    }

    @Test
    fun `utan något att besluta ritas inget märke`() {
        setShell(beslutBadgeCount = 0)

        composeRule.onNodeWithContentDescription("Beslut").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Beslut, 0 nya").assertDoesNotExist()
    }

    @Test
    fun `Facit är en skärm i stacken under Beslut med bakåtpil`() {
        setShell()
        composeRule.onNodeWithContentDescription("Beslut").performClick()

        composeRule.runOnUiThread { navController.navigate(Routes.FACIT) }

        assertOnScreen(Routes.FACIT)
        composeRule.onNodeWithText("Facit").assertIsDisplayed()
        assertTabChrome(visible = false)
        composeRule.onNodeWithContentDescription("Tillbaka").performClick()
        assertOnScreen("beslut")
        assertTabChrome(visible = true)
    }

    @Test
    fun `bakåt från en annan flik går till Översikt`() {
        setShell()

        composeRule.onNodeWithContentDescription("Innehav").performClick()
        composeRule.onNodeWithContentDescription("Händelser").performClick()
        assertOnScreen("transaktioner")

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        assertOnScreen("hem")
        composeRule.onNodeWithContentDescription("Översikt").assertIsSelected()
    }

    @Test
    fun `bakåt stänger en öppen plusmeny i stället för att lämna fliken`() {
        setShell()
        composeRule.onNodeWithContentDescription("Innehav").performClick()
        composeRule.onNodeWithContentDescription("Lägg till").performClick()
        composeRule.onNodeWithContentDescription("Stäng meny").assertIsDisplayed()

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }

        composeRule.onNodeWithContentDescription("Lägg till").assertIsDisplayed()
        assertOnScreen("portfolj")
    }

    @Test
    fun `skärmdump av skalet på Översikt`() {
        composeRule.captureLightAndDark("AppNavigation_oversikt") {
            AppNavigationShell(isWorkerRunning = false) {
                stubRoutes.forEach { route -> composable(route) { Text("skärm:$route") } }
            }
        }
    }
}
