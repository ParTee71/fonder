package se.partee71.fonder.testing

import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import se.partee71.fonder.di.NetworkModule
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Singleton
import javax.net.SocketFactory

/**
 * Ersätter [NetworkModule] i alla `@HiltAndroidTest`: appens enda `OkHttpClient` (kurskällorna,
 * fondmetadata och växelkurserna går alla genom den) svarar direkt med 503 utan att öppna någon
 * socket, så att instrumenttester är oberoende av nätet och deterministiska (regel 2). Appen
 * behandlar svaret som ett vanligt nätverksfel. [NetworkGuard] räknar ändå varje försök till en
 * riktig anslutning, så att ett test kan fälla sig självt om något tar sig förbi.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [NetworkModule::class])
object OfflineNetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(OfflineInterceptor)
            .socketFactory(NetworkGuard.socketFactory)
            .build()
}

/** Svarar på varje anrop utan nätverk; anropet når aldrig OkHttps anslutningslager. */
private object OfflineInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        NetworkGuard.answeredOffline.incrementAndGet()
        return Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(503)
            .message("Offline i instrumenttest")
            .body("".toResponseBody())
            .build()
    }
}

/**
 * Vakten: [realConnectionAttempts] ska vara 0 efter varje test. Socketfabriken vägrar och räknar
 * varje anslutningsförsök som ändå når den.
 */
object NetworkGuard {
    val answeredOffline = AtomicInteger()
    val realConnectionAttempts = AtomicInteger()

    fun reset() {
        answeredOffline.set(0)
        realConnectionAttempts.set(0)
    }

    internal val socketFactory: SocketFactory = object : SocketFactory() {
        private fun refuse(): Nothing {
            realConnectionAttempts.incrementAndGet()
            throw IOException("Riktigt nätverksanrop i instrumenttest")
        }

        override fun createSocket(): Socket = refuse()
        override fun createSocket(host: String?, port: Int): Socket = refuse()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = refuse()
        override fun createSocket(host: InetAddress?, port: Int): Socket = refuse()
        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ): Socket = refuse()
    }
}
