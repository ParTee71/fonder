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
            val url = "$CHART_URL/$orderbookId/${from.format(DATE_FORMAT)}/${to.format(DATE_FORMAT)}?raw=true"
            execute(Request.Builder().url(url).build())
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

    private companion object {
        const val SEARCH_URL = "https://www.avanza.se/_api/fund-guide/search"
        const val GUIDE_URL = "https://www.avanza.se/_api/fund-guide/guide"
        const val CHART_URL = "https://www.avanza.se/_api/fund-guide/chart"
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
