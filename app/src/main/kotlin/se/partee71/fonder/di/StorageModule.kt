package se.partee71.fonder.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import se.partee71.fonder.data.room.AppDatabase

/**
 * Inställningarnas DataStore. [ReplaceFileCorruptionHandler] är inte valfri: filen ingår i
 * Android Auto Backup (`data_extraction_rules.xml`) och kan komma trunkerad tillbaka vid en
 * återställning, eller skrivas sönder om processen dör mitt i en skrivning. Utan hanterare
 * kastar `dataStore.data` `CorruptionException` in i varje flöde som läser den — och eftersom
 * `MainActivity` håller kvar splash-skärmen tills temat lästs blev följden en app som aldrig
 * startade, med hela Room-databasen oåtkomlig. Hellre återställda standardinställningar än
 * en låst app; fonder, transaktioner och kurser ligger i Room och rörs inte.
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "fonder_prefs",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

/**
 * Appens lagring på disk: Room-databasen och inställningarnas DataStore. En egen modul så att
 * instrumenttester med Hilt (`@TestInstallIn`) kan ersätta den med minnesbaserad lagring och
 * aldrig rör användardata på enheten de körs på.
 */
@Module
@InstallIn(SingletonComponent::class)
object StorageModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.dataStore

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
        .addMigrations(*AppDatabase.MIGRATIONS)
        .build()
}
