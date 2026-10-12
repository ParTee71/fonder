package se.partee71.fonder.testing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import se.partee71.fonder.data.room.AppDatabase
import se.partee71.fonder.di.StorageModule
import java.io.File
import java.util.UUID
import javax.inject.Singleton

/**
 * Ersätter [StorageModule] i alla `@HiltAndroidTest`: databasen i minnet och inställningarna i en
 * egen fil per test, så att ett instrumenttest aldrig läser eller skriver användarens data på
 * enheten det körs på – och varje test börjar tomt.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [StorageModule::class])
object InMemoryStorageModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create {
            File(context.cacheDir, "hilt-test-${UUID.randomUUID()}.preferences_pb")
        }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
}
