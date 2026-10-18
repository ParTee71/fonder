package se.partee71.fonder

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import dagger.hilt.android.testing.HiltTestApplication

/**
 * Instrumenttesternas runner: startar [HiltTestApplication] i stället för `FonderApp`, så att
 * `@HiltAndroidTest` kan starta `MainActivity` med appens riktiga Hilt-graf (utom lagringen, som
 * `InMemoryStorageModule` byter ut). Tester utan Hilt påverkas inte – de bygger sina beroenden
 * själva. Ingen appstart schemalägger kursuppdateringar; WorkManager initieras av testet som behöver det.
 */
class HiltTestRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader?, className: String?, context: Context?): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
