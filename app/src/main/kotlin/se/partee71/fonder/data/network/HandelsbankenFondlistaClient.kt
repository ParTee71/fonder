package se.partee71.fonder.data.network

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import se.partee71.fonder.di.IoDispatcher
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
    private val wafToken: FondlistaWafTokenSource,
    @param:IoDispatcher private val io: CoroutineDispatcher,
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

    private suspend fun get(url: String): String =
        fetchWithWafToken(url, wafToken::token) { token -> fetchOnce(url, token) }

    private suspend fun fetchOnce(url: String, token: String?): Response = withContext(io) {
        val request = Request.Builder().url(url).apply {
            if (token != null) header("Cookie", "${WafToken.COOKIE_NAME}=$token")
        }.build()
        httpClient.newCall(request).execute().use { response ->
            Response(response.code, response.header(WAF_ACTION_HEADER), response.body?.string())
        }
    }

    /** Det som behövs av ett svar för att avgöra om det är en kontroll — utbrutet för enhetstest. */
    internal data class Response(val code: Int, val wafAction: String?, val body: String?)

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

        /**
         * Hämtar [url] med fondlistans nyckel (issue #155). Utan nyckel, eller när källan svarar med en
         * kontroll trots nyckeln (den har gått ut), hämtas en ny via [token] och anropet görs om — en
         * gång. Ger källan fortfarande en kontroll kastas det, som för alla andra fel ([checkResponse]).
         */
        suspend fun fetchWithWafToken(
            url: String,
            token: suspend (rejected: String?) -> String?,
            fetch: suspend (token: String?) -> Response,
        ): String {
            var current = token(null)
            var response = fetch(current)
            if (response.wafAction != null) {
                val fresh = token(current)
                if (fresh != null && fresh != current) {
                    current = fresh
                    response = fetch(current)
                }
            }
            checkResponse(response.code, response.wafAction, url)
            val body = response.body
            if (body.isNullOrBlank()) throw IOException("Tomt svar från $url")
            return body
        }

        /** Satt av källans brandvägg (AWS WAF) när den kräver en webbläsarkontroll i stället för att svara. */
        const val WAF_ACTION_HEADER = "x-amzn-waf-action"

        /**
         * Kastar för allt utom ett riktigt svar. Källans brandvägg svarar ibland `202` med tom kropp
         * och [WAF_ACTION_HEADER] satt — en JavaScript-kontroll som appen inte kan lösa. Den räknas
         * som ett fel, inte som en tom sida: en tom katalog tolkades tidigare som "ingen fond går
         * att köpa hos Handelsbanken" och cachades i 30 dygn (issue #153).
         */
        fun checkResponse(code: Int, wafAction: String?, url: String) {
            if (wafAction != null) throw IOException("Källan krävde en webbläsarkontroll ($wafAction) för $url")
            if (code != 200) throw IOException("Oväntat svar ($code) från $url")
        }

        fun fundUrl(fundId: String): String = "$BASE_URL/funds/${urlEncode(fundId)}?hb=true&sa=2"

        private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
    }
}
