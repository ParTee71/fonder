package se.partee71.fonder.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.domain.model.AccountType
import se.partee71.fonder.domain.model.DownturnReaction
import se.partee71.fonder.domain.model.PrimaryGoal
import se.partee71.fonder.domain.model.RiskProfile
import se.partee71.fonder.domain.model.RiskProfileAnswers
import se.partee71.fonder.domain.model.TimeHorizon
import java.io.IOException

/**
 * Riskprofilens DataStore-rundtur (SET-3, issue #68) — genuin användardata, till skillnad
 * från övriga [PreferencesRepository]-fält som är ren cache-metadata (se
 * [se.partee71.fonder.data.repository.BackupPayload]). Ingen instrumenterad
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

    @Test
    fun `en malfordelning rundturar genom DataStore`() = runTest {
        val profile = RiskProfile(
            targetAllocation = mapOf(3 to 0.25, 4 to 0.5, 5 to 0.25),
            answers = RiskProfileAnswers(TimeHorizon.SJU_TILL_15_AR, DownturnReaction.GOR_INGET, PrimaryGoal.BALANSERAD),
        )

        repository.setRiskProfile(profile)

        assertEquals(profile, repository.riskProfile.first())
        assertEquals(mapOf(3 to 0.25, 4 to 0.5, 5 to 0.25), repository.riskProfile.first()?.effectiveAllocation)
    }

    @Test
    fun `en rastext sparad i det gamla SET-3-formatet avkodas till en migrerad fordelning, aldrig till null`() = runTest {
        // Regressionstest för dataförlustbuggen i issue #71: RiskProfile.riskProfile sväljer
        // avkodningsfel tyst (runCatching { … }.getOrNull()) — en profil sparad av #68:s app-
        // version (bara targetRiskLevel, aldrig targetAllocation) måste fortsätta avkodas.
        val riskProfileKey = stringPreferencesKey("risk_profile")
        dataStore.edit { it[riskProfileKey] = """{"targetRiskLevel":4}""" }

        val loaded = repository.riskProfile.first()

        assertEquals(mapOf(4 to 1.0), loaded?.effectiveAllocation)
    }

    // --- Kontotyp (SET-4, issue #70) ---

    @Test
    fun `accountType ar null innan nagot val gjorts`() = runTest {
        assertNull(repository.accountType.first())
    }

    @Test
    fun `accountType rundturar genom DataStore`() = runTest {
        repository.setAccountType(AccountType.ISK_KF)

        assertEquals(AccountType.ISK_KF, repository.accountType.first())
    }

    @Test
    fun `en ny sparning ersatter den gamla kontotypen`() = runTest {
        repository.setAccountType(AccountType.DEPA_AF)
        repository.setAccountType(AccountType.ISK_KF)

        assertEquals(AccountType.ISK_KF, repository.accountType.first())
    }

    // --- Trasig inställningsfil ---

    @Test
    fun `en korrupt installningsfil ger standardvarden i stallet for ett undantag`() = runTest {
        // Filen ingår i Android Auto Backup och kan komma trunkerad tillbaka vid en
        // återställning. Utan `corruptionHandler` (AppModule) kastar `data` en
        // CorruptionException in i MainViewModel.themeMode, och eftersom MainActivity håller
        // kvar splash-skärmen tills temat lästs startade appen aldrig igen.
        val corruptFile = tempFolder.newFile("corrupt.preferences_pb")
        corruptFile.writeBytes(byteArrayOf(0x42, 0x00, 0x13, 0x37, 0x7F))
        val corruptStore = PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            produceFile = { corruptFile },
        )

        val repo = PreferencesRepository(corruptStore)

        assertEquals(ThemeMode.AUTO, repo.themeMode.first())
        assertNull(repo.riskProfile.first())
        assertNull(repo.accountType.first())
        // Och den återställda filen går att skriva till igen.
        repo.setAccountType(AccountType.ISK_KF)
        assertEquals(AccountType.ISK_KF, repo.accountType.first())
    }

    @Test
    fun `ett IO-fel vid lasning ger standardvarden i stallet for att kastas vidare`() = runTest {
        // Sista skyddet: även om filen inte är korrupt kan själva läsningen fela (t.ex. under
        // en pågående återställning). Flödena får aldrig kasta — de samlas i viewModelScope.
        val repo = PreferencesRepository(FailingDataStore())

        assertEquals(ThemeMode.AUTO, repo.themeMode.first())
        assertNull(repo.riskProfile.first())
        assertNull(repo.accountType.first())
        assertNull(repo.lastPriceSyncEpochMillis.first())
    }
}

/** DataStore vars läsning alltid felar — se testet ovan. */
private class FailingDataStore : DataStore<Preferences> {
    override val data: Flow<Preferences> = flow { throw IOException("kunde inte läsa inställningarna") }

    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
        throw IOException("kunde inte skriva inställningarna")
}
