package se.partee71.fonder.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.RiskProfileAnswers
import se.partee71.fonder.domain.model.TimeHorizon

/**
 * Riskprofilens DataStore-rundtur (SET-3, issue #68) — genuin användardata, till skillnad
 * från övriga [PreferencesRepository]-fält som är ren cache-metadata (se
 * [se.partee71.fonder.data.repository.StubBackupRepository]). Ingen instrumenterad
 * DataStore krävs: [PreferenceDataStoreFactory.create] fungerar direkt i ett JVM-enhetstest.
 */
class PreferencesRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var repository: PreferencesRepository

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(produceFile = { tempFolder.newFile("preferences_test.preferences_pb") })
        repository = PreferencesRepository(dataStore)
    }

    @Test
    fun `riskProfile ar null innan nagon profil sparats`() = runTest {
        assertNull(repository.riskProfile.first())
    }

    @Test
    fun `riskProfile med svar rundturar genom DataStore`() = runTest {
        val profile = RiskProfile(
            targetRiskLevel = 4,
            answers = RiskProfileAnswers(TimeHorizon.SJU_TILL_15_AR, DownturnReaction.GOR_INGET, PrimaryGoal.BALANSERAD),
        )

        repository.setRiskProfile(profile)

        assertEquals(profile, repository.riskProfile.first())
    }

    @Test
    fun `profil satt direkt utan enkat overlever rundturen och blandas inte ihop med ingen profil`() {
        runTest {
            val profile = RiskProfile(targetRiskLevel = 2, answers = null)

            repository.setRiskProfile(profile)

            val loaded = repository.riskProfile.first()
            assertEquals(2, loaded?.targetRiskLevel)
            assertNull(loaded?.answers)
            // Skilt tillstånd från "aldrig satt" — en satt profil utan enkätsvar är fortfarande en satt profil.
            assertEquals(profile, loaded)
        }
    }

    @Test
    fun `en ny sparning ersatter den gamla profilen`() = runTest {
        repository.setRiskProfile(RiskProfile(targetRiskLevel = 2))
        repository.setRiskProfile(RiskProfile(targetRiskLevel = 5, answers = RiskProfileAnswers(TimeHorizon.OVER_15_AR, DownturnReaction.KOPER_MER, PrimaryGoal.MAXIMAL_TILLVAXT)))

        val loaded = repository.riskProfile.first()

        assertEquals(5, loaded?.targetRiskLevel)
    }
}
