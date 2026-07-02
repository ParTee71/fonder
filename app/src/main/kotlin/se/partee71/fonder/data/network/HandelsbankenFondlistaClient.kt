package se.partee71.fonder.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tunn HTTP-klient mot handelsbanken.fondlista.se (se issue #2/#3). Publik källa, ingen
 * inloggning krävs. Returnerar rå HTML — parsning sker i [HandelsbankenHtmlParser].
 */
@Singleton
class HandelsbankenFondlistaClient @Inject constructor(
    private val httpClient: OkHttpClient,
) : FondlistaHtmlSource {
    /** Hämtar historik-sidan för en fond. [fundId] = null ger fortfarande fondkatalogen (dropdownen). */
    override suspend fun fetchHistoryPage(fundId: String?, from: LocalDate, to: LocalDate): String =
        withContext(Dispatchers.IO) {
            val url = buildHistoryUrl(fundId, from, to)
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Oväntat svar (${response.code}) från $url")
                }
                response.body?.string() ?: throw IOException("Tomt svar från $url")
            }
        }

    private fun buildHistoryUrl(fundId: String?, from: LocalDate, to: LocalDate): String {
        val start = urlEncode("${from.format(DATE_FORMAT)} 00:00:00")
        val end = urlEncode("${to.format(DATE_FORMAT)} 00:00:00")
        return buildString {
            append(BASE_URL)
            append("?company=1")
            if (!fundId.isNullOrBlank()) {
                append("&fundid=").append(urlEncode(fundId))
            }
            append("&startdate=").append(start)
            append("&enddate=").append(end)
            append("&s=nav")
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val BASE_URL = "https://handelsbanken.fondlista.se/shb/sv/history"
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
