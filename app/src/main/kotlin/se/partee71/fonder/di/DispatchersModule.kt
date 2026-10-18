package se.partee71.fonder.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Dispatcher för blockerande I/O – nätverk, filer, Drive. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dispatcher för tungt räknande, t.ex. att tolka en stor HTML-sida. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Huvudtråden – för API:er som kräver den, t.ex. WebView. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/**
 * Det enda stället som nämner `Dispatchers` (scopet `[dispatchers]` i `ui-forbidden.txt`). Övrig
 * kod tar en [CoroutineDispatcher] i konstruktorn, så att testerna kan ge en testdispatcher.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {
    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main
}
