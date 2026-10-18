package se.partee71.fonder.ui.riskprofil

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.data.repository.FakeFundMetadataRepository
import se.partee71.fonder.testing.testPreferencesRepository
import se.partee71.fonder.ui.theme.FonderTheme
import se.partee71.fonder.worker.FakeFundPriceRefreshScheduler

/**
 * Kvitteringen av "sparad" (skill android:kotlin-flows, "One-Off Events Belong in UiState"):
 * [RiskProfilScreen] går tillbaka exakt en gång och kvitterar till ViewModel:n — inte igen när
 * skärmen byggs upp på nytt (rotation), och inte bortslarvad när skärmen inte visades då
 * profilen sparades.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RiskProfilScreenSavedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @get:Rule
    val tempFolder = TemporaryFolder()

    /** DataStore skriver på testets egen dispatcher, som drivs fram explicit i [settle]. */
    private val dataStoreDispatcher = StandardTestDispatcher()

    private val metadata = object : FakeFundMetadataRepository() {
        override suspend fun knownRiskLevels(): List<Int> = listOf(1, 2, 3, 4, 5, 6)
    }

    private fun viewModel() = RiskProfilViewModel(
        testPreferencesRepository(dataStoreDispatcher, tempFolder, "riskprofil_screen"),
        metadata,
        FakeFundPriceRefreshScheduler()
    )

    /** Driver DataStore och huvudtråden fram omväxlande tills båda är stilla. */
    private fun settle() {
        repeat(5) {
            dataStoreDispatcher.scheduler.advanceUntilIdle()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun `sparad profil gar tillbaka en gang och kvitteras, aven efter rotation`() {
        val vm = viewModel()
        var backs = 0
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { FonderTheme { RiskProfilScreen(onSaved = { backs++ }, viewModel = vm) } }
        settle()

        vm.onAllocationPercentChanged(3, "100")
        settle()
        assertTrue(vm.uiState.value.canSave)
        vm.save()
        settle()
        assertEquals(1, backs)
        assertFalse(vm.uiState.value.saved)

        // Rotation: kompositionen byggs om med samma ViewModel — ingen andra navigering.
        restoration.emulateSavedInstanceStateRestore()
        settle()
        assertEquals(1, backs)
    }

    @Test
    fun `sparad medan skarmen inte visas gar tillbaka nar den visas igen`() {
        val vm = viewModel()
        var backs = 0
        var shown by mutableStateOf(true)
        composeRule.setContent {
            FonderTheme { if (shown) RiskProfilScreen(onSaved = { backs++ }, viewModel = vm) }
        }
        settle()
        vm.onAllocationPercentChanged(3, "100")
        settle()
        assertTrue(vm.uiState.value.canSave)

        // Sparningen startas, och skärmen försvinner innan den hunnit skrivas klart.
        vm.save()
        shown = false
        composeRule.waitForIdle()
        settle()
        assertEquals(0, backs)

        shown = true
        settle()
        assertEquals(1, backs)
        assertFalse(vm.uiState.value.saved)
    }
}
