package se.partee71.fonder.domain.model

import kotlinx.serialization.Serializable

/**
 * Ett enskilt bytesförslag (HEM-8, issue #70), sparat med sin utgångspunkt så utfallet kan
 * mätas senare mot att ha behållit innehavet — "facit". Skrivs av den periodiska
 * bakgrundskörningen ([se.partee71.fonder.worker.FundPriceUpdateWorker]), aldrig vid
 * appstart eller manuell uppdatering (samma princip som HEM-6:s jämförelseskanning).
 *
 * Genuin användardata: förslagstidpunkten och NAV-utgångsläget kan inte återskapas ur
 * NAV-historiken i efterhand, till skillnad från t.ex. [FundMetadata.cheapestAlternativeIsin]
 * — ingår därför i backup-kontraktet (NFR-1), se
 * [se.partee71.fonder.data.repository.BackupPayload].
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
 * @param batchEpochMillis identifierar den **körning** som spelade in raden, så en plan kan
 *   läsas som en enhet. Dygnet räcker inte: backstopen kör var 12:e timme, så två körningar
 *   landar normalt samma dygn och kan ha räknat fram olika planer — visades de ihop blev det
 *   en "plan" som aldrig räknats fram (issue #75). 0 = inspelad före den här versionen, då
 *   bara dygnet är känt.
 * @param followed null tills användaren markerat förslaget som genomfört (SET-5, issue #80).
 * @param kind vilken sorts råd raden bär (issue #91). Defaulten är inte kosmetisk: varje rad
 *   inspelad före den här versionen **är** ett riskplansbyte, och migreringen sätter samma
 *   värde. Typen måste följa med i backup-kontraktet — utan den går ett återställt [followed]
 *   inte att tolka, eftersom de två sorterna mäts var för sig i facit.
 */
@Serializable
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
    val batchEpochMillis: Long = 0,
    val kind: SuggestionKind = SuggestionKind.RISK_PLAN,
)

/**
 * Sorten av inspelat råd (issue #91) — de mäts var för sig i facit (SET-5) och läses av olika
 * vyer. [RISK_PLAN] är bytesplanens byten mot riskprofilens målfördelning (HEM-8); [FEE] är
 * avgiftsbytet till ett billigare, likvärdigt alternativ (ANA-9).
 *
 * Skillnaden är inte bara en etikett: de besvarar olika frågor. Ett avgiftsbyte görs för en
 * **känd** besparing, ett riskplansbyte för ett förväntat utfall — att slå ihop dem till ett
 * snitt hade dolt att den ena sortens råd är säkrare än den andra. Dessutom läser Hems
 * bytesplan bara [RISK_PLAN]: en [FEE]-rad där hade presenterats som "1. Sälj X → Köp Y" i en
 * plan den aldrig ingick i.
 */
@Serializable
enum class SuggestionKind {
    RISK_PLAN,
    FEE,
}
