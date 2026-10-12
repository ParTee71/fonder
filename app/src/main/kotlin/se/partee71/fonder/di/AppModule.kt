package se.partee71.fonder.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.partee71.fonder.data.room.AppDatabase
import se.partee71.fonder.data.room.daos.FundDao
import se.partee71.fonder.data.room.daos.FundMetadataDao
import se.partee71.fonder.data.room.daos.FundPriceDao
import se.partee71.fonder.data.room.daos.FxRateDao
import se.partee71.fonder.data.room.daos.SuggestionDismissalDao
import se.partee71.fonder.data.room.daos.SuggestionRecordDao
import se.partee71.fonder.data.room.daos.SwitchWatchDao
import se.partee71.fonder.data.room.daos.TransactionDao

/**
 * DAO:erna ur databasen som [StorageModule] ger. Lagringen själv (Room-filen och DataStore) ligger
 * i en egen modul så att instrumenttester kan byta ut just den mot minnesbaserad lagring.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

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

    @Provides
    fun provideSwitchWatchDao(db: AppDatabase): SwitchWatchDao = db.switchWatchDao()

    @Provides
    fun provideSuggestionDismissalDao(db: AppDatabase): SuggestionDismissalDao = db.suggestionDismissalDao()
}
