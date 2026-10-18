package se.partee71.fonder.testing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestDispatcher
import org.junit.rules.TemporaryFolder
import se.partee71.fonder.data.datastore.PreferencesRepository

/**
 * En riktig [PreferencesRepository] på en DataStore-fil i [folder], som skriver på testets egen
 * [dispatcher] — utan den kör DataStore på en frikopplad `Dispatchers.IO`-scope och en
 * Turbine-väntan blir tidsberoende (skill testing-strategy → flakiness som redan bitit, punkt 1).
 */
fun testPreferencesRepository(dispatcher: TestDispatcher, folder: TemporaryFolder, name: String): PreferencesRepository = PreferencesRepository(
    PreferenceDataStoreFactory.create(
        scope = CoroutineScope(dispatcher + SupervisorJob()),
        produceFile = { folder.newFile("$name.preferences_pb") }
    )
)
