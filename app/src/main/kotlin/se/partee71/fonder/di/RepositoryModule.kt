package se.partee71.fonder.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import se.partee71.fonder.data.auth.AuthRepository
import se.partee71.fonder.data.auth.StubAuthRepository
import se.partee71.fonder.data.repository.BackupRepository
import se.partee71.fonder.data.repository.FundPriceRepository
import se.partee71.fonder.data.repository.RoomTransactionRepository
import se.partee71.fonder.data.repository.StubBackupRepository
import se.partee71.fonder.data.repository.StubFundPriceRepository
import se.partee71.fonder.data.repository.TransactionRepository

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    abstract fun bindTransactionRepository(impl: RoomTransactionRepository): TransactionRepository

    @Binds
    abstract fun bindFundPriceRepository(impl: StubFundPriceRepository): FundPriceRepository

    @Binds
    abstract fun bindBackupRepository(impl: StubBackupRepository): BackupRepository

    @Binds
    abstract fun bindAuthRepository(impl: StubAuthRepository): AuthRepository
}
