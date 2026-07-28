package se.partee71.fonder.data.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Parsar Riksbankens SWEA-svar. Isolerad från [RiksbankFxClient] så ett formatbrott är lätt
 * att lokalisera (samma princip som [HandelsbankenHtmlParser] och [AvanzaJsonParser]).
 *
 * Format: `[{"date":"2026-07-24","value":9.71697}, …]`.
 */
object RiksbankJsonParser {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Observation(val date: String, val value: Double? = null)

    /** Observationer → [FxRatePoint]. Rader utan värde eller med oläsbart datum hoppas över. */
    fun parseObservations(responseJson: String): List<FxRatePoint> =
        runCatching { json.decodeFromString<List<Observation>>(responseJson) }
            .getOrNull()
            ?.mapNotNull { observation ->
                val rate = observation.value ?: return@mapNotNull null
                val date = runCatching { LocalDate.parse(observation.date) }.getOrNull() ?: return@mapNotNull null
                FxRatePoint(epochDay = date.toEpochDay(), rate = rate)
            }
            ?: emptyList()
}
