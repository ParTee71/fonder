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
import se.partee71.fonder.data.room.AppDatabase
import se.partee71.fonder.data.room.daos.FundDao
import se.partee71.fonder.data.room.daos.FundMetadataDao
import se.partee71.fonder.data.room.daos.FundPriceDao
import se.partee71.fonder.data.room.daos.FxRateDao
import se.partee71.fonder.data.room.daos.SuggestionRecordDao
import se.partee71.fonder.data.room.daos.TransactionDao
import javax.inject.Singleton

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
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.dataStore

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME)
            .addMigrations(*AppDatabase.MIGRATIONS)
            .build()

    @Provides
    fun provideFundDao(db: AppDatabase): FundDao = db.fundDao()

    @Provides
    fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()

    @Provides
    fun provideFundPriceDao(db: AppDatabase): FundPriceDao = db.fundPriceDao()

    @Provides
    fun provideFxRateDao(db: AppDatabase): FxRateDao = db.fxRateDao()

    @Provides
    fun provideFundMetadataDao(db: AppDatabase): FundMetadataDao = db.fundMetadataDao()

    @Provides
    fun provideSuggestionRecordDao(db: AppDatabase): SuggestionRecordDao = db.suggestionRecordDao()
}
