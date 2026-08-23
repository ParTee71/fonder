package se.partee71.fonder.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.partee71.fonder.data.auth.AuthRepository
import se.partee71.fonder.data.auth.FirebaseAuthRepository
import se.partee71.fonder.data.imports.PdfBoxTextExtractor
import se.partee71.fonder.data.imports.PdfTextExtractor
import se.partee71.fonder.data.network.AvanzaClient
import se.partee71.fonder.data.network.AvanzaPriceSource
import se.partee71.fonder.data.network.AvanzaSource
import se.partee71.fonder.data.network.FondlistaFundPageSource
import se.partee71.fonder.data.network.FondlistaHtmlSource
import se.partee71.fonder.data.network.FxRateSource
import se.partee71.fonder.data.network.HandelsbankenFondlistaClient
import se.partee71.fonder.data.network.IsinPriceHistorySource
import se.partee71.fonder.data.network.RiksbankFxClient
import se.partee71.fonder.data.repository.AvanzaFundMetadataRepository
import se.partee71.fonder.data.repository.BackupRepository
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.HandelsbankenFundPriceRepository
import se.partee71.fonder.data.repository.RoomSuggestionRecordRepository
import se.partee71.fonder.data.repository.RoomTransactionRepository
import se.partee71.fonder.data.repository.LocalBackupRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import se.partee71.fonder.worker.WorkManagerFundPriceRefreshScheduler
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindTransactionRepository(impl: RoomTransactionRepository): TransactionRepository

    @Binds
    abstract fun bindFundPriceRepository(impl: HandelsbankenFundPriceRepository): FundPriceRepository

    @Binds
    abstract fun bindFundMetadataRepository(impl: AvanzaFundMetadataRepository): FundMetadataRepository

    @Binds
    abstract fun bindSuggestionRecordRepository(impl: RoomSuggestionRecordRepository): SuggestionRecordRepository

    @Binds
    abstract fun bindFondlistaHtmlSource(impl: HandelsbankenFondlistaClient): FondlistaHtmlSource

    @Binds
    abstract fun bindFondlistaFundPageSource(impl: HandelsbankenFondlistaClient): FondlistaFundPageSource

    @Binds
    abstract fun bindFxRateSource(impl: RiksbankFxClient): FxRateSource

    @Binds
    abstract fun bindAvanzaSource(impl: AvanzaClient): AvanzaSource

    @Binds
    abstract fun bindPdfTextExtractor(impl: PdfBoxTextExtractor): PdfTextExtractor

    @Binds
    abstract fun bindBackupRepository(impl: LocalBackupRepository): BackupRepository

    @Binds
    abstract fun bindAuthRepository(impl: FirebaseAuthRepository): AuthRepository

    @Binds
    abstract fun bindFundPriceRefreshScheduler(impl: WorkManagerFundPriceRefreshScheduler): FundPriceRefreshScheduler

    companion object {
        /**
         * Prioritetsordning för ISIN-baserad kurshistorik (se KRAVLISTA TP-14) — idag bara
         * Avanza; Nordnet/Morningstar undersöktes men saknade en bekräftat inloggningsfri
         * sökväg från ISIN. Lista (inte Set-multibinding) för att ordningen ska vara
         * deterministisk.
         */
        @Provides
        @Singleton
        fun provideIsinPriceHistorySources(avanza: AvanzaPriceSource): List<IsinPriceHistorySource> =
            listOf(avanza)
    }
}
