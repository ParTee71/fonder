package se.partee71.fonder.data.network

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import se.partee71.fonder.di.MainDispatcher
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nyckeln (`aws-waf-token`) som handelsbanken.fondlista.se kräver sedan källan började svara
 * vanliga HTTP-anrop med en webbläsarkontroll (AWS WAF, issue #155). Kontrollen är ett
 * JavaScript-pussel som bara en webbläsare löser; nyckeln den ger gäller sedan i flera dygn och
 * godtas även i appens egna anrop.
 */
interface FondlistaWafTokenSource {
    /**
     * Gällande nyckel, eller null om ingen gick att få. [rejected] är nyckeln källan just svarat
     * med en kontroll trots (den har gått ut): då ges en **annan** nyckel, löst på nytt om det
     * behövs. Null betyder att ingen nyckel avvisats.
     */
    suspend fun token(rejected: String?): String?
}

/**
 * Löser kontrollen i en osynlig [WebView] — samma motor som en webbläsare på telefonen — och läser
 * nyckeln ur WebViews kakburk ([CookieManager]), som sparas mellan appstarter. En ny laddning görs
 * bara när nyckeln saknas eller just avvisats, och samtidiga anrop väntar på samma laddning och
 * använder dess nyckel.
 * Inget om användaren skickas: sidan som laddas är en publik fondsida.
 */
@Singleton
class WebViewFondlistaWafTokenSource @Inject constructor(
    @ApplicationContext private val context: Context,
    @param:MainDispatcher private val main: CoroutineDispatcher,
) : FondlistaWafTokenSource {

    private val mutex = Mutex()

    override suspend fun token(rejected: String?): String? = mutex.withLock {
        withContext(main) {
            val cookies = runCatching { CookieManager.getInstance() }.getOrNull() ?: return@withContext null
            // Har ett annat anrop redan löst om kontrollen medan detta väntade på låset används den
            // nyckeln — samtidiga avvisningar ger en enda ny laddning, inte en var.
            val current = WafToken.fromCookieHeader(cookies.getCookie(HandelsbankenFondlistaClient.BASE_URL))
            if (current != null && current != rejected) return@withContext current
            solveChallenge(cookies, rejected)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveChallenge(cookies: CookieManager, rejected: String?): String? {
        val webView = runCatching { WebView(context) }.onFailure { e ->
            Log.w(TAG, "Kunde inte skapa WebView för fondlistans kontroll", e)
        }.getOrNull() ?: return null
        return try {
            webView.settings.javaScriptEnabled = true
            webView.webViewClient = WebViewClient()
            webView.loadUrl(CHALLENGE_URL)
            withTimeoutOrNull(TIMEOUT_MS) {
                var token: String? = null
                // Den avvisade nyckeln kan ligga kvar i kakburken tills sidan satt en ny — vänta på en annan.
                while (token == null) {
                    delay(POLL_MS)
                    token = WafToken.fromCookieHeader(cookies.getCookie(HandelsbankenFondlistaClient.BASE_URL))
                        ?.takeIf { it != rejected }
                }
                cookies.flush()
                token
            }.also { if (it == null) Log.w(TAG, "Fondlistans kontroll gav ingen nyckel inom ${TIMEOUT_MS / 1000} s") }
        } finally {
            webView.stopLoading()
            webView.destroy()
        }
    }

    private companion object {
        const val TAG = "FondlistaWafToken"
        const val TIMEOUT_MS = 20_000L
        const val POLL_MS = 250L

        /** En lätt, publik sida på samma värd — vilken som helst ger kontrollen och därmed nyckeln. */
        const val CHALLENGE_URL = "${HandelsbankenFondlistaClient.BASE_URL}/funds/SHB0000161?hb=true&sa=2"
    }
}

/** Ren tolkning av kakhuvudet, utbruten för enhetstest. */
object WafToken {
    const val COOKIE_NAME = "aws-waf-token"

    /** Värdet på [COOKIE_NAME] i ett `Cookie`-huvud (`a=1; aws-waf-token=x`), eller null. */
    fun fromCookieHeader(header: String?): String? =
        header?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$COOKIE_NAME=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
}
