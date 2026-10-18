package se.partee71.fonder.domain.usecase

import java.time.LocalDate
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.TransactionType

/**
 * Vad en transaktion gör med bytet (BYT-2, issue #144) — svaret på bytesväljaren i formuläret
 * (TRX-1) och i PDF-importen (IMP-6). Användaren väljer; appen föreslår bara ett förval enligt
 * [SwitchTransitions.suggestFor] och gissar aldrig utöver det.
 */
sealed interface SwitchDecision {

    /** Transaktionen hör inte till något byte — "Inget byte". */
    data object None : SwitchDecision

    /**
     * Säljet är planraden [recordId]s sälj (HEM-8): bevakningen startas ur raden med dess målnivå
     * [toLevel] och raden blir **pågående** (BYT-1). Finns raden inte längre startas bevakningen
     * fristående, som [Standalone].
     */
    data class ForSuggestion(val recordId: Long, val toLevel: Int) : SwitchDecision

    /** Säljet är ett eget byte utanför planen — "Fristående bevakning" (SLD-5). */
    data object Standalone : SwitchDecision

    /** Köpet avslutar bevakningen [watchId] (ANA-12) — stängs som köpt på köpdagen med köptransaktionen. */
    data class ClosesWatch(val watchId: Long) : SwitchDecision

    /**
     * Säljet är den öppna, okopplade bevakningen [watchId]s sälj — svaret "ja" på frågan om en
     * bevakning startad ur ett förslag, samma eller en annan dag ([SwitchTransitions.linkableWatch]).
     * Bevakningen kopplas till säljet och tar likviden ur det; från en annan dag flyttas säljdagen
     * dit och kandidaternas nollpunkt ankras om. Aldrig utan uttryckligt svar. Hinner bevakningen
     * kopplas eller stängas av något annat ges inget [RecordTransaction.Recorded.watchId] — säljet
     * är sparat, kopplingen gjordes inte, och det sägs till användaren.
     */
    data class LinksWatch(val watchId: Long) : SwitchDecision

    /**
     * Köpet avslutar den bevakning som **säljraden [entryIndex] i samma import** startar
     * ([RecordTransaction.recordAll], IMP-6) — bevakningen finns inte förrän säljraden skrivits,
     * så den kan inte pekas ut med ett id. Säljraden måste ligga före köpraden; annars, eller om
     * säljraden inte startade någon bevakning, behandlas köpet som [None].
     */
    data class ClosesWatchOfEntry(val entryIndex: Int) : SwitchDecision
}

/**
 * Bytesväljarens innehåll för en transaktion: [options] i visningsordning och det [preselected]
 * valet, null när frågan måste besvaras uttryckligen ([required]).
 *
 * [SwitchDecision.None] står alltid med; ett köp utan något att koppla till har bara det, och
 * väljaren kan då utelämnas.
 */
data class SwitchChoices(
    val options: List<SwitchDecision>,
    val preselected: SwitchDecision?
) {
    /** Sant när inget förval finns och användaren måste svara innan transaktionen sparas. */
    val required: Boolean get() = preselected == null

    /** Sant när det bara finns "Inget byte" — inget att fråga om. */
    val isTrivial: Boolean get() = options.size == 1
}

/**
 * Förvalsreglerna för bytesväljaren (BYT-2, issue #144) — ren domänlogik utan Room eller
 * nätverk, så formuläret och importen ger samma förval för samma transaktion.
 *
 * Reglerna är avsiktligt få och deterministiska (lärdomen från #143: varje gissning gav nya
 * kantfall):
 *
 * - **Sälj** av säljfonden i exakt **en** öppen planrad (HEM-8, inte redan pågående) → förval
 *   raden. Flera rader → ingen gissning, frågan ställs. Ingen rad → "Inget byte" förvalt, och
 *   "Fristående bevakning" är ett aktivt val.
 * - **Köp** av en kandidat eller den föreslagna fonden i den **enda** öppna bevakningen → förval
 *   avslut. Annars inget förval när det finns något att koppla till — även ett köp av en helt
 *   annan fond frågas om, utan beloppsheuristik. Finns ingen bevakning att koppla till är
 *   "Inget byte" förvalt.
 * - Ett köp **före** säljdagen eller mot en **utgången** bevakning erbjuds aldrig som avslut:
 *   pengarna fanns inte än, respektive bytet beskriver inte längre ett läge som finns.
 */
object SwitchTransitions {

    /**
     * Väljarens alternativ och förval för en transaktion av [type] i fonden [fundIsin] på [epochDay].
     *
     * @param plan den visade bytesplanen, **märkt** av [InProgressSwitches] (en pågående rad har
     *   redan sitt sälj) och utan avstådda par — samma lista som Beslut visar.
     * @param openWatches de öppna bevakningarna ([SwitchWatchRepository.observeOpen]).
     * @param buyIsinByRecordId källförslagens köpfond per rad-id, för bevakningar startade ur ett
     *   förslag — den föreslagna fonden är ett förval även när den inte lagts som kandidat.
     * @return för ett köp utan ISIN eller en fond utan ISIN: bara [SwitchDecision.None].
     */
    fun suggestFor(
        type: TransactionType,
        fundIsin: String?,
        epochDay: Long,
        plan: List<SwitchPlanResolver.Suggestion>,
        openWatches: List<SwitchWatch>,
        today: LocalDate,
        buyIsinByRecordId: Map<Long, String> = emptyMap()
    ): SwitchChoices = when (type) {
        TransactionType.SALJ -> forSale(fundIsin, plan)
        TransactionType.KOP -> forPurchase(fundIsin, epochDay, openWatches, today, buyIsinByRecordId)
    }

    private fun forSale(fundIsin: String?, plan: List<SwitchPlanResolver.Suggestion>): SwitchChoices {
        if (fundIsin == null) return SwitchChoices(listOf(SwitchDecision.None), SwitchDecision.None)
        val rows = plan
            .filter { it.sellIsin == fundIsin && !it.inProgress }
            .sortedBy { it.planIndex }
            .map { SwitchDecision.ForSuggestion(it.recordId, it.toLevel) }
        val options = listOf(SwitchDecision.None, SwitchDecision.Standalone) + rows
        val preselected = when (rows.size) {
            0 -> SwitchDecision.None
            1 -> rows.single()
            else -> null
        }
        return SwitchChoices(options, preselected)
    }

    private fun forPurchase(
        fundIsin: String?,
        epochDay: Long,
        openWatches: List<SwitchWatch>,
        today: LocalDate,
        buyIsinByRecordId: Map<Long, String>
    ): SwitchChoices {
        if (fundIsin == null) return SwitchChoices(listOf(SwitchDecision.None), SwitchDecision.None)
        val eligible = openWatches
            .filter { it.isOpen && !SwitchWatchCalc.isExpired(it, today) && it.soldAtEpochDay <= epochDay }
            .sortedWith(compareBy({ it.soldAtEpochDay }, { it.id }))
        val options = listOf(SwitchDecision.None) + eligible.map { SwitchDecision.ClosesWatch(it.id) }
        val preselected = when {
            eligible.isEmpty() -> SwitchDecision.None
            eligible.size == 1 && expects(eligible.single(), fundIsin, buyIsinByRecordId) -> SwitchDecision.ClosesWatch(eligible.single().id)
            else -> null
        }
        return SwitchChoices(options, preselected)
    }

    /** Sant när [isin] är en bevakad kandidat eller källförslagets köpfond i [watch]. */
    private fun expects(watch: SwitchWatch, isin: String, buyIsinByRecordId: Map<Long, String>): Boolean = watch.candidates.any { it.isin == isin } || watch.sourceRecordId?.let { buyIsinByRecordId[it] } == isin

    /**
     * Den öppna bevakning **utan känd transaktion** av säljfonden inom ±[SwitchWatchCalc.WATCH_TTL_DAYS]
     * från [epochDay] — samma dag inräknad — som ett sälj kan kopplas till **efter att användaren
     * tillfrågats** ([SwitchDecision.LinksWatch], BYT-1). Äldst om flera; null om
     * ingen. En utgången bevakning erbjuds inte.
     *
     * Samma dag ingår med flit: ett sälj som sparas som "Inget byte" startar inget och kopplar
     * inget, så en bevakning startad ur förslaget samma dag hade annars förblivit okopplad och
     * aldrig räknats som likvid (BYT-3). Frågan ställs bara när anroparen vill — ingen bevakning
     * kopplas utan svar; bara "Fristående"/"för planrad" kopplar samma dag utan fråga
     * ([RecordTransaction.startWatchForSale]).
     */
    fun linkableWatch(fundIsin: String?, epochDay: Long, openWatches: List<SwitchWatch>, today: LocalDate): SwitchWatch? = linkableWatches(fundIsin, epochDay, openWatches, today).firstOrNull()

    /**
     * Alla bevakningar [linkableWatch] kan peka ut, äldst först — importen behöver nästa när en
     * tidigare säljrad redan tagit den första: en bevakning kopplas av högst en rad. En koppling som
     * hade gjort bevakningen **utgången** på säljdagen (säljet är äldre än `WATCH_TTL_DAYS`) erbjuds
     * inte — den hade stängts av nästa bakgrundskörning utan att någonsin kunna avslutas med ett köp.
     */
    fun linkableWatches(fundIsin: String?, epochDay: Long, openWatches: List<SwitchWatch>, today: LocalDate): List<SwitchWatch> {
        if (fundIsin == null) return emptyList()
        return openWatches
            .filter { it.isOpen && it.sellTransactionId == null && it.sellIsin == fundIsin && !SwitchWatchCalc.isExpired(it, today) }
            .filter { abs(it.soldAtEpochDay - epochDay) <= SwitchWatchCalc.WATCH_TTL_DAYS }
            .filterNot { SwitchWatchCalc.isExpired(it.copy(soldAtEpochDay = epochDay), today) }
            .sortedWith(compareBy({ it.soldAtEpochDay }, { it.id }))
    }
}

/**
 * Bytesväljarens indata ur repositorierna — **bara cachen**, aldrig nätverket — samlade en gång
 * så formuläret (en transaktion) och importen (många rader) räknar förvalen ur samma ögonblick
 * av tabellerna. Planen är den Beslut visar ([PendingDecisionsSource]: märkt pågående, utan
 * avstådda), bevakningarna de öppna, och köpfonderna slås upp riktat ur bevakningarnas
 * källförslag ([SuggestionRecordRepository.observeByIds]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SwitchChoicesSource @Inject constructor(
    private val pendingDecisionsSource: PendingDecisionsSource,
    private val switchWatchRepository: SwitchWatchRepository,
    private val suggestionRecordRepository: SuggestionRecordRepository
) {

    /**
     * Indatan till [SwitchTransitions] — [suggestFor] och [linkableWatch] per transaktion utan nya läsningar.
     *
     * [cheaperAlternatives] är Besluts billigare alternativ (ANA-9). De ingår **inte** i
     * [suggestFor]s förval (det gäller bara planen), men Följ på ett sådant förslag öppnar
     * formuläret med förslagets rad, och [SwitchQuestions.forTransaction] behöver raden för att
     * kunna erbjuda den (issue #144).
     */
    data class Inputs(
        val plan: List<SwitchPlanResolver.Suggestion>,
        val openWatches: List<SwitchWatch>,
        val buyIsinByRecordId: Map<Long, String>,
        val today: LocalDate,
        val cheaperAlternatives: List<SwitchPlanResolver.Suggestion> = emptyList()
    ) {
        fun suggestFor(type: TransactionType, fundIsin: String?, epochDay: Long): SwitchChoices = SwitchTransitions.suggestFor(type, fundIsin, epochDay, plan, openWatches, today, buyIsinByRecordId)

        /** Som [SwitchTransitions.linkableWatch], utan bevakningar i [exclude] — de en tidigare rad i samma import redan kopplar. */
        fun linkableWatch(fundIsin: String?, epochDay: Long, exclude: Set<Long> = emptySet()): SwitchWatch? = SwitchTransitions.linkableWatches(fundIsin, epochDay, openWatches, today).firstOrNull { it.id !in exclude }
    }

    fun observe(today: () -> LocalDate = LocalDate::now): Flow<Inputs> = switchWatchRepository.observeOpen().flatMapLatest { watches ->
        val ids = watches.mapNotNullTo(mutableSetOf()) { it.sourceRecordId }
        combine(pendingDecisionsSource.observe(), suggestionRecordRepository.observeByIds(ids)) { pending, records ->
            Inputs(
                plan = pending.switchPlan,
                openWatches = watches,
                buyIsinByRecordId = records.associate { it.id to it.buyIsin },
                today = today(),
                cheaperAlternatives = pending.cheaperAlternatives.map { it.suggestion }
            )
        }
    }
}
