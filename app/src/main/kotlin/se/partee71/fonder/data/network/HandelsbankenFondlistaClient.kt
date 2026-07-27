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
 * Tunn HTTP-klient mot handelsbanken.fondlista.se (se issue #2/#3, utökad i #37). Publik
 * källa, ingen inloggning krävs. Returnerar rå HTML — parsning sker i
 * [HandelsbankenHtmlParser].
 */
@Singleton
class HandelsbankenFondlistaClient @Inject constructor(
    private val httpClient: OkHttpClient,
) : FondlistaHtmlSource, FondlistaFundPageSource {

    /**
     * Hämtar historik-sidan. [fundId] = null ger fortfarande fondkatalogen (dropdownen),
     * [company] = null ger **hela** plattformens katalog i stället för ett enskilt bolags
     * (se KRAVLISTA TP-18).
     */
    override suspend fun fetchHistoryPage(fundId: String?, company: String?, from: LocalDate, to: LocalDate): String =
        get(buildHistoryUrl(fundId, company, from, to))

    /** Hämtar en enskild fonds sida, som bär fondens ISIN (KRAVLISTA TP-18). */
    override suspend fun fetchFundPage(fundId: String): String = get(fundUrl(fundId))

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Oväntat svar (${response.code}) från $url")
            }
            response.body?.string() ?: throw IOException("Tomt svar från $url")
        }
    }

    /** Rena URL-byggare, utbrutna för att kunna enhetstestas utan riktig HTTP (som [AvanzaClient.chartUrl]). */
    internal companion object {
        const val BASE_URL = "https://handelsbanken.fondlista.se/shb/sv"
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        /**
         * `company` utelämnas när den är null — det är skillnaden mellan hela plattformens
         * katalog och ett enskilt fondbolags (KRAVLISTA TP-18) och därför inget som får
         * defaultas till Handelsbanken av misstag.
         */
        fun buildHistoryUrl(fundId: String?, company: String?, from: LocalDate, to: LocalDate): String {
            val start = urlEncode("${from.format(DATE_FORMAT)} 00:00:00")
            val end = urlEncode("${to.format(DATE_FORMAT)} 00:00:00")
            return buildString {
                append(BASE_URL).append("/history")
                append("?startdate=").append(start)
                append("&enddate=").append(end)
                if (!company.isNullOrBlank()) {
                    append("&company=").append(urlEncode(company))
                }
                if (!fundId.isNullOrBlank()) {
                    append("&fundid=").append(urlEncode(fundId))
                }
                append("&s=nav")
            }
        }

        fun fundUrl(fundId: String): String = "$BASE_URL/funds/${urlEncode(fundId)}?hb=true&sa=2"

        private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
