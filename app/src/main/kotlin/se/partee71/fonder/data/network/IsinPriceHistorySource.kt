package se.partee71.fonder.data.network

import se.partee71.fonder.domain.model.IsinPricePoint
import java.time.LocalDate

/**
 * Källa för kurshistorik nyckelbar på **ISIN** — i motsats till [FondlistaHtmlSource], som
 * nycklas på Handelsbankens FundId (se KRAVLISTA TP-9). Flera implementationer kan provas i
 * fallback-ordning av `FundPriceRepository.refreshSince`/`suggestIsin` (idag bara
 * [AvanzaPriceSource] — Nordnet/Morningstar undersöktes men saknade en bekräftat
 * inloggningsfri sökväg från ISIN, se KRAVLISTA TP-14 för riskavsnitt).
 */
interface IsinPriceHistorySource {
    /** Daglig kurshistorik för [isin] inom [from]..[to] (inklusive). Tom lista om fonden inte hittas i källan. */
    suspend fun fetchHistory(isin: String, from: LocalDate, to: LocalDate): List<IsinPricePoint>

    /** Bästa ISIN-gissning för en fond med namnet [fundName], eller null om ingen rimlig träff. */
    suspend fun suggestIsin(fundName: String): String?
}
