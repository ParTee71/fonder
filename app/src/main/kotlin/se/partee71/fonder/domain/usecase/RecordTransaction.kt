package se.partee71.fonder.domain.usecase

import kotlinx.coroutines.flow.first
import se.partee71.fonder.data.repository.FundMetadataRepository
import se.partee71.fonder.data.repository.SuggestionRecordRepository
import se.partee71.fonder.data.repository.SwitchWatchRepository
import se.partee71.fonder.data.repository.TransactionRepository
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.SuggestionRecord
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCloseReason
import se.partee71.fonder.domain.model.Transaction
import se.partee71.fonder.domain.model.TransactionType
import se.partee71.fonder.worker.FundPriceRefreshScheduler
import java.time.LocalDate
import javax.inject.Inject

/**
 * Den **enda skrivvägen** för transaktioner (BYT-2, issue #143/#144): formuläret (TRX-1),
 * PDF-importen (IMP-6) och Excel-importen (IMP-5) går alla hit i stället för till
 * [TransactionRepository.addTransaction] direkt. Varje transaktion bär sitt svar på
 * bytesväljaren ([Entry.decision], [SwitchDecision]) — användaren har svarat, appen gissar
 * aldrig: ett sälj kan starta bevakningen ur en planrad eller fristående, ett köp kan avsluta en
 * bevakning med köptransaktionen som koppling (BYT-1). Förvalen räknas av [SwitchTransitions].
 *
 * Här bor också bytets övriga handlingar: ett sälj i Händelser startar bevakningen med den
 * **verkliga** säljdagen och likviden ur transaktionen ([startWatchForSale], SLD-5), ett
 * förslag startar den utan transaktion ([startWatchFromSuggestion]), och en okopplad bevakning
 * kopplas efter fråga ([SwitchDecision.LinksWatch]). Dedupen går på säljtransaktionen
 * ([SwitchWatchRepository.hasOpenForTransaction]) med (fond, dag) som reserv för bevakningar utan
 * känd transaktion — två startvägar kan aldrig ge två bevakningar för samma sälj.
 *
 * **Planen räknas om** efter varje skrivning hit som kan ändra den (BYT-3, HEM-8): innehaven
 * eller likviden på väg har ändrats. Begäran går till [FundPriceRefreshScheduler] utan forcerad
 * kursuppdatering och koalesceras av WorkManager; en import begär **en** omräkning, inte en per
 * rad. Beroendet domän → worker är medvetet (se README): omräkningen är en del av vad en
 * transaktion *betyder*, och hade varje anropare själv behövt minnas den hade en glömd begäran
 * lämnat planen föreslående ett sälj för ett gap som redan är fyllt.
 *
 * Appen utför **aldrig** ett byte. Allt här registrerar vad användaren gjort.
 */
class RecordTransaction @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val switchWatchRepository: SwitchWatchRepository,
    private val suggestionRecordRepository: SuggestionRecordRepository,
    private val fundMetadataRepository: FundMetadataRepository,
    private val scheduler: FundPriceRefreshScheduler,
) {

    /** En transaktion att skriva, med användarens svar på bytesväljaren ("Inget byte" om väljaren inte visats). */
    data class Entry(val transaction: Transaction, val decision: SwitchDecision = SwitchDecision.None)

    /**
     * Resultatet av en skriven rad: transaktionens [id] och den bevakning raden rörde —
     * startad eller kopplad av ett sälj, stängd av ett köp — eller null när inget byte rördes
     * (valet var "Inget byte", bevakningen var redan stängd, eller fonden saknar ISIN).
     */
    data class Recorded(val id: Long, val watchId: Long? = null)

    /** Skriver transaktionen med sitt bytesval och begär en omräkning av planen. */
    suspend fun record(entry: Entry, today: LocalDate = LocalDate.now()): Recorded = recordAll(listOf(entry), today).single()

    /**
     * Skriver raderna i given ordning och returnerar deras utfall i samma ordning; **en**
     * omräkning av planen begärs efteråt, inte en per rad. Säljrader ska ligga före de köprader
     * som avslutar deras bevakning ([SwitchDecision.ClosesWatchOfEntry]) — och före ett köp som
     * avslutar en bevakning säljraden kopplar ([SwitchDecision.LinksWatch]): raderna skrivs i
     * ordning i samma pass, så köpet ser länken.
     *
     * Raderna skrivs en i taget, inte i en databastransaktion: varje rad är en verklig händelse
     * och går aldrig förlorad — avbryts importen efter rad n finns rad 1..n, och importflödena
     * (IMP-5/IMP-6) är idempotenta under pågående körning. En gemensam transaktion hade krävt att
     * use caset kände till Room, för ett skydd mot ett fall som inte tappar data.
     */
    suspend fun recordAll(entries: List<Entry>, today: LocalDate = LocalDate.now()): List<Recorded> {
        if (entries.isEmpty()) return emptyList()
        // Fonderna läses en gång per import, inte per rad.
        val funds = transactionRepository.observeFunds().first()
        val recorded = mutableListOf<Recorded>()
        for (entry in entries) {
            val id = transactionRepository.addTransaction(entry.transaction)
            val saved = entry.transaction.copy(id = id)
            val watchId = when (val decision = entry.decision) {
                SwitchDecision.None -> null
                SwitchDecision.Standalone -> if (saved.type == TransactionType.SALJ) startWatch(saved, funds, today) else null
                is SwitchDecision.ForSuggestion -> if (saved.type == TransactionType.SALJ) startWatch(saved, funds, today, decision) else null
                is SwitchDecision.LinksWatch -> linkWatch(saved, decision.watchId, today)
                is SwitchDecision.ClosesWatch -> closeWatch(saved, funds, decision.watchId)
                is SwitchDecision.ClosesWatchOfEntry -> recorded.getOrNull(decision.entryIndex)?.watchId?.let { closeWatch(saved, funds, it) }
            }
            recorded += Recorded(id, watchId)
        }
        scheduler.triggerSwitchPlanScan(force = false)
        return recorded
    }

    /**
     * Startar bevakningen av ett pågående byte ur en **sparad** säljtransaktion ("Bevaka
     * alternativ" i Händelser, SLD-5/ANA-12, och "Fristående bevakning" i väljaren): säljdag och
     * likvid (belopp − avgift) ur transaktionen, aldrig ur dagens datum — utvecklingen mäts från
     * dagen pengarna faktiskt frigjordes. Målnivån är säljfondens **egen** risknivå ur
     * metadatacachen — det närmaste "något likvärdigt" utan en plan att mäta mot; saknas nivån
     * startas bevakningen utan förslag, en gissad nivå hade gett alternativ på fel risk
     * (ANA-4-principen). Inget nätverksanrop görs.
     *
     * En öppen bevakning utan känd transaktion för samma fond och **samma säljdag** (startad ur
     * ett förslag innan säljet registrerades, eller från före 0.67.0) **är** det här säljets byte:
     * den kopplas och får säljets likvid i stället för att en andra startas — säljdagen står, så
     * kandidaternas nollpunkt rörs inte. Är den utgången men ännu inte stängd av
     * bakgrundskörningen blockerar den som förut (fond + dag): att starta en ny bredvid hade gett
     * två bevakningar för samma sälj. En bevakning från en annan dag kopplas aldrig utan att
     * användaren tillfrågats ([SwitchDecision.LinksWatch]).
     *
     * Begär en omräkning av planen: likviden på väg har ändrats (BYT-3).
     *
     * @return bevakningens id — den nystartade eller den kopplade — eller null när säljet redan
     *   har en bevakning, när [sale] inte är ett sparat sälj eller när fonden saknar ISIN
     *   (kandidaterna nycklas på ISIN).
     */
    suspend fun startWatchForSale(sale: Transaction, today: LocalDate = LocalDate.now()): Long? {
        val watchId = startWatch(sale, transactionRepository.observeFunds().first(), today)
        scheduler.triggerSwitchPlanScan(force = false)
        return watchId
    }

    /**
     * [startWatchForSale]s kärna, utan omräkningsbegäran. Med [suggestion] startas bevakningen ur
     * planraden (målnivå ur raden, raden blir pågående, BYT-1); finns raden inte längre startas
     * den fristående med nivån ur cachen — ett påhittat rad-id vore ett påstående.
     *
     * @return bevakningens id bara när den startades eller länken faktiskt skrevs — en koppling
     *   som förlorade mot en samtidig skrivning ger null, så ingen rad kan peka på fel bevakning.
     */
    private suspend fun startWatch(sale: Transaction, funds: List<Fund>, today: LocalDate, suggestion: SwitchDecision.ForSuggestion? = null): Long? {
        if (sale.type != TransactionType.SALJ || sale.id == 0L) return null
        val fund = funds.firstOrNull { it.fundId == sale.fundId }
        val isin = fund?.isin ?: return null
        // Dedupen går på transaktionen; bevakningar med länk matchar bara på den, så två sälj
        // samma dag i samma fond kan få varsin.
        if (switchWatchRepository.hasOpenForTransaction(sale.id)) return null

        val record = suggestion?.let { recordById(it.recordId) }
        val existing = switchWatchRepository.findOpenWithoutTransactionFor(isin, sale.epochDay)
        // En okopplad bevakning samma dag är säljets — utom när säljet är för planrad R och
        // bevakningen startats ur ett **annat** förslag: då hade R aldrig kopplats och målnivån
        // blivit fel. En bevakning utan förslag tar över R (rad och målnivå) när den kopplas.
        if (existing != null && (record == null || existing.sourceRecordId == null || existing.sourceRecordId == record.id)) {
            if (SwitchWatchCalc.isExpired(existing, today)) return null
            val adopt = record?.takeIf { existing.sourceRecordId == null }
            val linked = switchWatchRepository.linkSellTransaction(
                existing.id, sale.id, sale.amount - sale.fee,
                sourceRecordId = adopt?.id, targetLevel = adopt?.let { suggestion?.toLevel },
            )
            return if (linked) existing.id else null
        }

        return switchWatchRepository.start(
            SwitchWatch(
                sellIsin = isin,
                sellFundName = fund.name,
                soldAtEpochDay = sale.epochDay,
                proceedsKr = sale.amount - sale.fee,
                targetLevel = suggestion?.takeIf { record != null }?.toLevel
                    ?: fundMetadataRepository.cachedMetadataFor(listOf(isin))[isin]?.risk,
                sourceRecordId = record?.id,
                sellTransactionId = sale.id,
            ),
        )
    }

    /**
     * Ett sälj med [SwitchDecision.LinksWatch]: kopplar det sparade säljet till en öppen bevakning
     * utan känd transaktion — användaren har svarat ja ([SwitchTransitions.linkableWatch] pekade ut
     * den). Från en **annan** dag flyttas säljdagen till transaktionens, likviden tas ur den och
     * kandidaternas nollpunkt nollas (den var ankrad på fel dag); kandidaterna, även de manuellt
     * tillagda, ligger kvar. Från **samma** dag kopplas den utan att nollpunkten rörs.
     *
     * Utgången prövas mot den **nya** dagen: en bevakning som blev utgången av flytten hade stängts
     * av nästa bakgrundskörning utan att kunna avslutas med ett köp.
     *
     * @return bevakningens id när kopplingen gjordes; null när säljet inte är ett sälj, redan har en
     *   bevakning, eller bevakningen inte finns, redan är kopplad eller stängd, eller vore utgången.
     */
    private suspend fun linkWatch(sale: Transaction, watchId: Long, today: LocalDate): Long? {
        if (sale.type != TransactionType.SALJ) return null
        if (switchWatchRepository.hasOpenForTransaction(sale.id)) return null
        val watch = switchWatchRepository.observe(watchId).first() ?: return null
        if (!watch.isOpen || watch.sellTransactionId != null) return null
        if (SwitchWatchCalc.isExpired(watch, today) || SwitchWatchCalc.isExpired(watch.copy(soldAtEpochDay = sale.epochDay), today)) return null
        val linked = switchWatchRepository.linkSellTransaction(watchId, sale.id, sale.amount - sale.fee, soldAtEpochDay = sale.epochDay)
        return if (linked) watchId else null
    }

    /**
     * Tar bort en transaktion (TRX-2). Repositoryt nollar länken i den bevakning transaktionen
     * startade eller avslutade; bevakningen ligger kvar. Planen räknas om: innehaven har ändrats,
     * och en bevakning vars sälj inte finns längre räknas inte som likvid på väg (BYT-3).
     */
    suspend fun delete(transactionId: Long) {
        transactionRepository.deleteTransaction(transactionId)
        scheduler.triggerSwitchPlanScan(force = false)
    }

    /**
     * Startar bevakningen ur ett kvitterat förslag **utan** transaktion — "Bevaka alternativ" på
     * Beslut och Fonddetalj (HEM-8/ANA-10). Säljdagen blir [today]: kvitteringen görs när bytet
     * gjorts, och ett påhittat tidigare datum hade mätt kandidaternas utveckling över dagar
     * pengarna inte var fria. Ingen [SwitchWatch.sellTransactionId] — den transaktionen är inte
     * känd här, och ett påhittat id vore ett påstående. Dedupen är därför (fond, dag): en öppen
     * bevakning av säljfonden från [today] blockerar.
     *
     * Ingen omräkning: utan säljtransaktion räknas bevakningen inte som likvid på väg (BYT-3),
     * så planen är oförändrad.
     *
     * @return bevakningens id, null när säljfonden redan har en öppen bevakning från [today].
     */
    suspend fun startWatchFromSuggestion(suggestion: SwitchPlanResolver.Suggestion, today: LocalDate = LocalDate.now()): Long? {
        if (switchWatchRepository.hasOpenFor(suggestion.sellIsin, today.toEpochDay())) return null
        return switchWatchRepository.start(
            SwitchWatch(
                sellIsin = suggestion.sellIsin,
                sellFundName = suggestion.sellFundName,
                soldAtEpochDay = today.toEpochDay(),
                proceedsKr = suggestion.switchValueKr,
                targetLevel = suggestion.toLevel,
                sourceRecordId = suggestion.recordId,
            ),
        )
    }

    /**
     * Ett köp med [SwitchDecision.ClosesWatch] — formuläret, PDF-importen och "Köpte den här", som
     * går via köpformuläret (ANA-12, issue #144): stänger bevakningen som
     * [SwitchWatchCloseReason.KOPT] på **köpdagen** med köptransaktionen som koppling. Startades
     * bevakningen ur ett förslag markeras förslaget som genomfört (`followed`, SET-5) **bara** när
     * det köpta var den föreslagna fonden — facit mäter följda råd, och ett byte till något annat är
     * inte rådet som följdes.
     *
     * Stängningen är en villkorad uppdatering i databasen (`closedAtEpochDay IS NULL`), och bara
     * anropet vars uppdatering träffade raden kvitterar — två samtidiga avslut kan därför aldrig
     * skriva över varandra eller kvittera två gånger.
     *
     * @return bevakningens id när den stängdes nu; null när den inte finns eller redan var stängd.
     */
    private suspend fun closeWatch(purchase: Transaction, funds: List<Fund>, watchId: Long): Long? {
        if (purchase.type != TransactionType.KOP) return null
        val boughtIsin = funds.firstOrNull { it.fundId == purchase.fundId }?.isin
        val closed = closeAsBought(watchId, boughtIsin, LocalDate.ofEpochDay(purchase.epochDay), buyTransactionId = purchase.id)
        return if (closed) watchId else null
    }

    private suspend fun closeAsBought(watchId: Long, boughtIsin: String?, closedAt: LocalDate, buyTransactionId: Long?): Boolean {
        val watch = switchWatchRepository.observe(watchId).first() ?: return false
        if (!switchWatchRepository.close(watchId, SwitchWatchCloseReason.KOPT, closedAt, boughtIsin, buyTransactionId)) return false

        val recordId = watch.sourceRecordId ?: return true
        val record = recordById(recordId)
        if (record != null && boughtIsin != null && record.buyIsin == boughtIsin) {
            suggestionRecordRepository.setFollowed(recordId, true)
        }
        return true
    }

    /** Riktad uppslagning — aldrig hela historiken för en rad. */
    private suspend fun recordById(recordId: Long): SuggestionRecord? =
        suggestionRecordRepository.observeByIds(setOf(recordId)).first().firstOrNull()
}
