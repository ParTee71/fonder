package se.partee71.fonder.domain.model

/**
 * Ett enskilt bytesförslag (HEM-8, issue #70), sparat med sin utgångspunkt så utfallet kan
 * mätas senare mot att ha behållit innehavet — "facit". Skrivs av den periodiska
 * bakgrundskörningen ([se.partee71.fonder.worker.FundPriceUpdateWorker]), aldrig vid
 * appstart eller manuell uppdatering (samma princip som HEM-6:s jämförelseskanning).
 *
 * Genuin användardata: förslagstidpunkten och NAV-utgångsläget kan inte återskapas ur
 * NAV-historiken i efterhand, till skillnad från t.ex. [FundMetadata.cheapestAlternativeIsin]
 * — ska ingå i backup-kontraktet (NFR-1) när Drive-backup (TP-7) byggs, se
 * [se.partee71.fonder.data.repository.StubBackupRepository].
 *
 * Redovisning/visning av facit är ett eget, senare issue — bara inspelningen ligger här.
 *
 * @param planIndex platsen i den rangordnade bytesplanen (0 = först) — gör det mätbart om
 *   lägre rankade byten presterar sämre än de högst rankade, innan taket på antal byten
 *   per plan höjs.
 * @param switchValueKr beloppet förslaget avser — bytet storleksbestäms till gapet, inte till
 *   hela positionen ([se.partee71.fonder.domain.usecase.SwitchPlanCalc]), så beloppet är en
 *   del av rådet och måste visas. Null bara för rader inspelade före issue #75, då bytet
 *   alltid avsåg hela positionen men beloppet inte sparades — visas då utan belopp i stället
 *   för med ett påhittat.
 * @param followed null tills en framtida "markera som genomförd"-funktion (utanför det här
 *   issuets scope) sätter den.
 */
data class SuggestionRecord(
    val id: Long = 0,
    val suggestedAtEpochDay: Long,
    val planIndex: Int,
    val sellIsin: String,
    val buyIsin: String,
    val sellNavAtSuggestion: Double,
    val buyNavAtSuggestion: Double,
    val switchValueKr: Double? = null,
    val followed: Boolean? = null,
)
