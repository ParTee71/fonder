package se.partee71.fonder.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.fonder.ui.components.APP_FLOATING_TOOLBAR_TAG
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.ui.components.AppTopBar as LibraryTopBar
import se.partee71.ui.components.TopBarSize

/**
 * Navigeringsskalet (NAV-7, NAV-8, UI-8) i JVM med Robolectric – riktiga flikrad, plusmeny,
 * toppradsval och `NavDisplay`, men enkla ersättare för skärmarna så att testet inte behöver Hilt:
 * fyra flikar med Översikt som start, plusmenyn på varje flik som öppnar sina skärmar, Inställningar
 * bakom ikonen på Översikt, ingen länk till Sålda fonder på Händelser (SLD-1), flikraden och
 * plusmenyn borta på en djupare skärm, bakåt från en annan flik till Översikt, att en flik behåller
 * sitt tillstånd och sin ViewModel vid flikbyte, att stacken återställs efter rotation, och att en
 * ramskärm ([FrameKey]) ritar sin egen topprad med chromets indikator via `LocalTopBarStatus`.
 */
@RunWith(RobolectricTestRunner::class)
class AppNavigationShellTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val backStack = AppBackStack()

    /** Ersättarskärmens text – flikarnas och skärmarnas gamla ruttnamn, så skärmdumpen är oförändrad. */
    private fun name(key: AppKey): String = when (key) {
        OversiktKey -> "hem"
        InnehavKey -> "portfolj"
        BeslutKey -> "beslut"
        HandelserKey -> "transaktioner"
        SettingsKey -> "settings"
        FacitKey -> "facit"
        is TransactionFormKey -> "transaction-form"
        ImportOrdersKey -> "import-orders"
        ImportHoldingsKey -> "import-holdings"
        FundSearchKey -> "fund-search"
        is FondDetaljKey -> "fond/${key.fundId}"
        else -> key.toString()
    }

    /**
     * Ersättarskärmar: Innehav öppnar en fond, och varje skärm räknar tryck i sparat tillstånd och
     * i sin ViewModel.
     */
    private fun stubEntry(key: AppKey): NavEntry<AppKey> = NavEntry(key) {
        val viewModel: StubViewModel = viewModel()
        viewModels[key] = viewModel
        var taps by rememberSaveable { mutableIntStateOf(0) }
        Column {
            Text("skärm:${name(key)}", Modifier.clickable { taps++ })
            Text("tryck:$taps")
            if (key == InnehavKey) {
                Text("öppna fond", Modifier.clickable { backStack.push(FondDetaljKey(FUND_ID)) })
            }
        }
    }

    private val viewModels = mutableMapOf<AppKey, StubViewModel>()

    private fun setShell(beslutBadgeCount: Int = 0) = composeRule.setContent {
        FonderTheme {
            AppNavigationShell(
                isWorkerRunning = false,
                beslutBadgeCount = beslutBadgeCount,
                backStack = backStack,
                entryProvider = ::stubEntry
            )
        }
    }

    private fun assertOnScreen(name: String) = composeRule.onNodeWithText("skärm:$name").assertIsDisplayed()

    private fun pressBack() {
        // Bakåthanterarna följer kompositionen – vänta in den innan bakåt trycks.
        composeRule.waitForIdle()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    }

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
            "Översikt" to "hem"
        ).forEach { (label, route) ->
            composeRule.onNodeWithContentDescription(label).performClick()
            assertOnScreen(route)
            composeRule.onNodeWithContentDescription(label).assertIsSelected()
            // Flikens rubrik i innehållet, ingen bakåtpil – Händelser är en ramskärm och ritar sin egen.
            if (Screen.forKey(backStack.current)?.key is FrameKey) {
                composeRule.onAllNodesWithText(label).assertCountEquals(0)
            } else {
                composeRule.onNodeWithText(label).assertIsDisplayed()
            }
            composeRule.onNodeWithContentDescription("Tillbaka").assertDoesNotExist()
            assertTabChrome(visible = true)
        }
    }

    @Test
    fun `plusmenyn öppnar sina skärmar och döljs med flikraden på djupare skärmar`() {
        setShell()

        listOf(
            "Ny transaktion" to "transaction-form",
            "Importera avräkningsnota" to "import-orders",
            "Importera innehav" to "import-holdings",
            "Bevaka ny fond" to "fund-search"
        ).forEach { (label, route) ->
            composeRule.onNodeWithContentDescription("Lägg till").performClick()
            composeRule.onNodeWithText(label).performClick()

            assertOnScreen(route)
            assertTabChrome(visible = false)
            // Transaktionsformuläret är en ramskärm utan skalets topprad – systemets bakåt gäller alla.
            pressBack()
            assertOnScreen("hem")
            assertTabChrome(visible = true)
        }
    }

    @Test
    fun `plusmenyn stängs när man lämnar fliken och är stängd när man kommer tillbaka`() {
        setShell()
        composeRule.onNodeWithContentDescription("Lägg till").performClick()
        composeRule.onNodeWithContentDescription("Stäng meny").assertIsDisplayed()

        composeRule.runOnUiThread { backStack.push(SettingsKey) }
        assertOnScreen("settings")
        composeRule.onNodeWithContentDescription("Tillbaka").performClick()

        assertOnScreen("hem")
        composeRule.onNodeWithContentDescription("Lägg till").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Stäng meny").assertDoesNotExist()
    }

    @Test
    fun `inställningsikonen på Översikt öppnar Inställningar med bakåtpil`() {
        setShell()

        composeRule.onNodeWithContentDescription("Inställningar").performClick()

        assertOnScreen("settings")
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

        composeRule.runOnUiThread { backStack.push(FacitKey) }

        assertOnScreen("facit")
        composeRule.onNodeWithText("Facit").assertIsDisplayed()
        assertTabChrome(visible = false)
        composeRule.onNodeWithContentDescription("Tillbaka").performClick()
        assertOnScreen("beslut")
        assertTabChrome(visible = true)
    }

    @Test
    fun `Fonddetalj öppnas från Innehav och bakåt leder tillbaka till Innehav`() {
        setShell()
        composeRule.onNodeWithContentDescription("Innehav").performClick()

        composeRule.onNodeWithText("öppna fond").performClick()

        assertOnScreen("fond/$FUND_ID")
        composeRule.onNodeWithText("Fond").assertIsDisplayed()
        assertTabChrome(visible = false)
        pressBack()
        assertOnScreen("portfolj")
        composeRule.onNodeWithContentDescription("Innehav").assertIsSelected()
    }

    @Test
    fun `bakåt från en annan flik går till Översikt`() {
        setShell()

        composeRule.onNodeWithContentDescription("Innehav").performClick()
        composeRule.onNodeWithContentDescription("Händelser").performClick()
        assertOnScreen("transaktioner")

        pressBack()

        assertOnScreen("hem")
        composeRule.onNodeWithContentDescription("Översikt").assertIsSelected()
    }

    @Test
    fun `en flik behåller sitt tillstånd och sin ViewModel när man byter flik och tillbaka`() {
        setShell()
        composeRule.onNodeWithContentDescription("Innehav").performClick()
        composeRule.onNodeWithText("skärm:portfolj").performClick()
        composeRule.onNodeWithText("tryck:1").assertIsDisplayed()
        val innehavViewModel = viewModels.getValue(InnehavKey)

        composeRule.onNodeWithContentDescription("Beslut").performClick()
        assertOnScreen("beslut")
        composeRule.onNodeWithContentDescription("Innehav").performClick()

        assertOnScreen("portfolj")
        composeRule.onNodeWithText("tryck:1").assertIsDisplayed()
        assertSame(innehavViewModel, viewModels.getValue(InnehavKey))
        assertEquals(false, innehavViewModel.cleared)
    }

    @Test
    fun `en flik som lämnas med bakåt börjar om nästa gång`() {
        setShell()
        composeRule.onNodeWithContentDescription("Innehav").performClick()
        composeRule.onNodeWithText("skärm:portfolj").performClick()
        val innehavViewModel = viewModels.getValue(InnehavKey)

        pressBack()
        assertOnScreen("hem")
        composeRule.onNodeWithContentDescription("Innehav").performClick()

        composeRule.onNodeWithText("tryck:0").assertIsDisplayed()
        assertNotSame(innehavViewModel, viewModels.getValue(InnehavKey))
        assertEquals(true, innehavViewModel.cleared)
    }

    @Test
    fun `bakåt stänger en öppen plusmeny i stället för att lämna fliken`() {
        setShell()
        composeRule.onNodeWithContentDescription("Innehav").performClick()
        composeRule.onNodeWithContentDescription("Lägg till").performClick()
        composeRule.onNodeWithContentDescription("Stäng meny").assertIsDisplayed()

        pressBack()

        composeRule.onNodeWithContentDescription("Lägg till").assertIsDisplayed()
        assertOnScreen("portfolj")
    }

    @Test
    fun `stacken och en öppen plusmeny överlever att skärmen återskapas`() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            FonderTheme {
                AppNavigationShell(isWorkerRunning = false, entryProvider = ::stubEntry)
            }
        }
        composeRule.onNodeWithContentDescription("Innehav").performClick()
        composeRule.onNodeWithContentDescription("Lägg till").performClick()

        restoration.emulateSavedInstanceStateRestore()

        assertOnScreen("portfolj")
        composeRule.onNodeWithContentDescription("Innehav").assertIsSelected()
        composeRule.onNodeWithContentDescription("Stäng meny").assertIsDisplayed()
        // Bakåt stänger först menyn (även efter återskapandet), sedan lämnas fliken för Översikt.
        pressBack()
        composeRule.onNodeWithContentDescription("Lägg till").assertIsDisplayed()
        assertOnScreen("portfolj")
        pressBack()
        assertOnScreen("hem")
    }

    /** Ersättare för en ramskärm: bibliotekets topprad, som läser chromets status ur `LocalTopBarStatus`. */
    private fun frameEntry(key: AppKey): NavEntry<AppKey> = if (key is FrameKey) {
        NavEntry(key) {
            if (key is TabKey) {
                LibraryTopBar("ram:${name(key)}")
            } else {
                LibraryTopBar("ram:${name(key)}", size = TopBarSize.Small, onBack = { backStack.popIfTop(key) })
            }
        }
    } else {
        stubEntryWithoutTaps(key)
    }

    @Test
    fun `en ramskärm har ingen skalrubrik men chromets indikator i sin egen topprad`() {
        // Indikatorn snurrar oändligt – klockan stegas för hand i stället för att vänta på vila.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            FonderTheme { AppNavigationShell(isWorkerRunning = true, backStack = backStack, entryProvider = ::frameEntry) }
        }
        composeRule.runOnUiThread { backStack.push(SettingsKey) }
        composeRule.mainClock.advanceTimeBy(TRANSITION_MS)
        // En vanlig skärm djupare i stacken: skalets topprad med rubrik och indikator, en gång.
        composeRule.onNodeWithText("Inställningar").assertIsDisplayed()
        composeRule.onAllNodesWithContentDescription(WORKER_RUNNING).assertCountEquals(1)

        composeRule.runOnUiThread { backStack.push(ComponentGalleryKey) }
        composeRule.mainClock.advanceTimeBy(TRANSITION_MS)

        composeRule.onNodeWithText("ram:${name(ComponentGalleryKey)}").assertIsDisplayed()
        composeRule.onAllNodesWithText("Inställningar").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription("Tillbaka").assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription(WORKER_RUNNING).assertCountEquals(1)
        composeRule.onNodeWithContentDescription("Tillbaka").performClick()
        composeRule.mainClock.advanceTimeBy(TRANSITION_MS)
        composeRule.onNodeWithText("Inställningar").assertIsDisplayed()
    }

    @Test
    fun `Händelser är en ramskärm på en flik med egen rubrik och skalets flikrad och plusmeny`() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            FonderTheme { AppNavigationShell(isWorkerRunning = true, backStack = backStack, entryProvider = ::frameEntry) }
        }
        composeRule.runOnUiThread { backStack.select(Screen.Handelser) }
        composeRule.mainClock.advanceTimeBy(TRANSITION_MS)

        // Ramens rubrik och chromets indikator i den, en gång – ingen flikrubrik från skalet.
        composeRule.onNodeWithText("ram:transaktioner").assertIsDisplayed()
        composeRule.onAllNodesWithText("Händelser").assertCountEquals(0)
        composeRule.onAllNodesWithContentDescription(WORKER_RUNNING).assertCountEquals(1)
        composeRule.onAllNodesWithContentDescription("Tillbaka").assertCountEquals(0)
        // Flikraden och plusmenyn står kvar (NAV-7, NAV-8).
        composeRule.onNodeWithContentDescription("Händelser").assertIsSelected()
        composeRule.onNodeWithContentDescription("Lägg till").assertIsDisplayed()
    }

    @Test
    fun `skärmdump av skalet på Översikt`() {
        composeRule.captureLightAndDark("AppNavigation_oversikt") {
            AppNavigationShell(isWorkerRunning = false, entryProvider = ::stubEntryWithoutTaps)
        }
    }

    /** Skärmdumpens ersättare – bara skärmens namn, som före Navigation 3. */
    private fun stubEntryWithoutTaps(key: AppKey): NavEntry<AppKey> = NavEntry(key) { Text("skärm:${name(key)}") }

    class StubViewModel : ViewModel() {
        var cleared = false
            private set

        override fun onCleared() {
            cleared = true
        }
    }

    private companion object {
        const val FUND_ID = "SHB0000442"
        const val WORKER_RUNNING = "Uppdaterar kurser i bakgrunden"

        /** Längre än skärmbytets övergång (Transitions), så att bara målskärmen står kvar. */
        const val TRANSITION_MS = 2_000L
    }
}
