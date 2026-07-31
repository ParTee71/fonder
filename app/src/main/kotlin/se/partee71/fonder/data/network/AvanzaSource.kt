package se.partee71.fonder.data.network

import java.time.LocalDate

/**
 * Rå HTTP mot avanza.se:s odokumenterade fond-API. Abstraherat bort från [AvanzaClient] så
 * [AvanzaPriceSource] kan enhetstestas med en fejk, utan riktigt nätverk (samma princip som
 * [FondlistaHtmlSource]).
 */
interface AvanzaSource {
    /** Fritextsök (accepterar både ISIN och fondnamn) — rått JSON-svar. */
    suspend fun search(query: String): String

    /** Fondens detaljvy (bl.a. valuta) för [orderbookId] — rått JSON-svar. */
    suspend fun fetchGuide(orderbookId: String): String

    /** Daglig kurshistorik för [orderbookId] inom [from]..[to] — rått JSON-svar. */
    suspend fun fetchChart(orderbookId: String, from: LocalDate, to: LocalDate): String

    /**
     * Sökbar fondmetadata (avgift, kategori, fondtyp, risk, se KRAVLISTA TP-21) — [requestBody]
     * är en färdig JSON-payload från [AvanzaFundListRequestBuilder]. Rått JSON-svar, 20 fonder
     * per sida (källans sidstorlek, går inte att ändra).
     */
    suspend fun fetchFundList(requestBody: String): String
}
