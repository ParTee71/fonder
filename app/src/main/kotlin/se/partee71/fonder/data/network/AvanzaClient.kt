package se.partee71.fonder.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tunn HTTP-klient mot avanza.se:s odokumenterade fond-API — se risknotis i KRAVLISTA TP-14
 * (samma riskprofil som den befintliga Handelsbanken-källan, TP-10: odokumenterad, ingen
 * inloggning krävs, kan sluta fungera utan förvarning). Returnerar rått JSON — parsning sker
 * i [AvanzaJsonParser].
 */
@Singleton
class AvanzaClient @Inject constructor(
    private val httpClient: OkHttpClient,
) : AvanzaSource {

    override suspend fun search(query: String): String = withContext(Dispatchers.IO) {
        val body = """{"name":${jsonQuote(query)}}""".toRequestBody(JSON_MEDIA_TYPE)
        execute(Request.Builder().url(SEARCH_URL).post(body).build())
    }

    override suspend fun fetchGuide(orderbookId: String): String = withContext(Dispatchers.IO) {
        execute(Request.Builder().url("$GUIDE_URL/$orderbookId").build())
    }

    override suspend fun fetchChart(orderbookId: String, from: LocalDate, to: LocalDate): String =
        withContext(Dispatchers.IO) {
            execute(Request.Builder().url(chartUrl(orderbookId, from, to)).build())
        }

    private fun execute(request: Request): String =
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Oväntat svar (${response.code}) från ${request.url}")
            }
            response.body?.string() ?: throw IOException("Tomt svar från ${request.url}")
        }

    private fun jsonQuote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    internal companion object {
        const val SEARCH_URL = "https://www.avanza.se/_api/fund-guide/search"
        const val GUIDE_URL = "https://www.avanza.se/_api/fund-guide/guide"
        const val CHART_URL = "https://www.avanza.se/_api/fund-guide/chart"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        /**
         * Bygger chart-URL:en. **`resolution=DAY` är avgörande:** utan den nedsamplar Avanza
         * långa datumintervall (t.ex. "sedan köp", ~13 mån) till **veckopunkter**, så den
         * nyaste NAV:en appen får blir förra hela veckans — dagskurserna däremellan når aldrig
         * cachen. Det visade sig som "Kurs ej uppdaterad" för dag/vecka (POR-5/HEM-2) trots att
         * Avanza hade färskare dagsdata (senaste cachade blev alltid förra måndagens veckopunkt).
         * Ett `to`-datum i framtiden ger dessutom `400 Bad Request` från Avanza, så det clampas
         * aldrig förbi [today] (försvar mot en klocka/tidszon som ligger före serverns).
         */
        internal fun chartUrl(
            orderbookId: String,
            from: LocalDate,
            to: LocalDate,
            today: LocalDate = LocalDate.now(),
        ): String {
            val effectiveTo = if (to.isAfter(today)) today else to
            return "$CHART_URL/$orderbookId/${from.format(DATE_FORMAT)}/${effectiveTo.format(DATE_FORMAT)}?raw=true&resolution=DAY"
        }
    }
}
