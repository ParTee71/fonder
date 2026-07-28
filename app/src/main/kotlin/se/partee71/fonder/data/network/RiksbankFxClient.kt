package se.partee71.fonder.data.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Historiska växelkurser mot kronor från **Riksbankens SWEA-API** (se KRAVLISTA TP-20).
 * Öppet, ingen inloggning eller nyckel krävs, och till skillnad från appens övriga källor
 * (TP-10/TP-14) är det ett dokumenterat API från en myndighet — betydligt lägre risk att det
 * ändras utan förvarning.
 *
 * Serierna heter `SEK<valuta>PMI` och noteras **per 1 enhet** av valutan (verifierat
 * 2026-07-28 för USD, EUR, NOK, DKK, GBP, CHF och JPY — ingen av dem är noterad per 100, som
 * annars är vanligt för JPY). Historik finns från 1995. En valuta som saknar serie ger
 * `204 No Content`, vilket tolkas som "inga kurser" i stället för fel.
 */
@Singleton
class RiksbankFxClient @Inject constructor(
    private val httpClient: OkHttpClient,
) : FxRateSource {

    override suspend fun fetchRates(currency: String, from: LocalDate, to: LocalDate): List<FxRatePoint> =
        withContext(Dispatchers.IO) {
            val url = seriesUrl(currency, from, to)
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                // 204 = serien finns inte / inga observationer i intervallet. Inte ett fel.
                if (response.code == HTTP_NO_CONTENT) return@use emptyList()
                if (!response.isSuccessful) {
                    throw IOException("Oväntat svar (${response.code}) från $url")
                }
                val body = response.body?.string() ?: throw IOException("Tomt svar från $url")
                RiksbankJsonParser.parseObservations(body)
            }
        }

    internal companion object {
        const val BASE_URL = "https://api.riksbank.se/swea/v1/Observations"
        const val HTTP_NO_CONTENT = 204
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        /** Serie-id för en valuta, t.ex. USD → `SEKUSDPMI`. */
        fun seriesId(currency: String): String = "SEK${currency.uppercase()}PMI"

        fun seriesUrl(currency: String, from: LocalDate, to: LocalDate): String =
            "$BASE_URL/${seriesId(currency)}/${from.format(DATE_FORMAT)}/${to.format(DATE_FORMAT)}"
    }
}
