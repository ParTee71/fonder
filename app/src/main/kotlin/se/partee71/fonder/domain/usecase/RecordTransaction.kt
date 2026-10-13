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
import java.time.LocalDate
import javax.inject.Inject

/**
 * Den **enda skrivvägen** för transaktioner (BYT-2, issue #143): formuläret (TRX-1), PDF-importen
 * (IMP-6) och Excel-importen (IMP-5) går alla hit i stället för till
 * [TransactionRepository.addTransaction] direkt. I A rör en sparad transaktion **inget** byte —
 * bytesväljaren (planrad, fristående, avslutar bevakning) och omräkningen av planen kräver att
 * användaren tillfrågas och kommer i #144. Appen gissar aldrig.
 *
 * Här bor också byggstenarna bytets livscykel har i dag: ett sälj i Händelser startar bevakningen
 * med den **verkliga** säljdagen och likviden ur transaktionen ([startWatchForSale], SLD-5), ett
 * förslag startar den utan transaktion ([startWatchFromSuggestion]), och "Köpte den här" stänger
 * den och kvitterar förslaget bara när det köpta faktiskt var det föreslagna ([markBought]).
 * Dedupen går på säljtransaktionen ([SwitchWatchRepository.hasOpenForTransaction]) med (fond, dag)
 * som reserv för bevakningar utan känd transaktion — två startvägar kan aldrig ge två
 * bevakningar för samma sälj.
 *
 * Appen utför **aldrig** ett byte. Allt här registrerar vad användaren gjort.
 */
class RecordTransaction @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val switchWatchRepository: SwitchWatchRepository,
    private val suggestionRecordRepository: SuggestionRecordRepository,
    private val fundMetadataRepository: FundMetadataRepository,
) {

    /** Skriver transaktionen och returnerar dess id. */
    suspend fun record(transaction: Transaction): Long = transactionRepository.addTransaction(transaction)

    /**
     * Skriver raderna i given ordning och returnerar deras id:n i samma ordning.
     *
     * Raderna skrivs en i taget, inte i en databastransaktion: varje rad är en verklig händelse
     * och går aldrig förlorad — avbryts importen efter rad n finns rad 1..n, och importflödena
     * (IMP-5/IMP-6) är idempotenta under pågående körning. En gemensam transaktion hade krävt att
     * use caset kände till Room, för ett skydd mot ett fall som inte tappar data.
     */
    suspend fun recordAll(transactions: List<Transaction>): List<Long> =
        transactions.map { transactionRepository.addTransaction(it) }

    /**
     * Startar bevakningen av ett pågående byte ur en **sparad** säljtransaktion ("Bevaka
     * alternativ" i Händelser, SLD-5/ANA-12): säljdag och likvid (belopp − avgift) ur
     * transaktionen, aldrig ur dagens datum — utvecklingen mäts från dagen pengarna faktiskt
     * frigjordes. Målnivån är säljfondens **egen** risknivå ur metadatacachen — det närmaste
     * "något likvärdigt" utan en plan att mäta mot; saknas nivån startas bevakningen utan
     * förslag, en gissad nivå hade gett alternativ på fel risk (ANA-4-principen). Inget
     * nätverksanrop görs.
     *
     * En öppen bevakning utan känd transaktion för samma fond och **samma säljdag** (startad ur
     * ett förslag innan säljet registrerades, eller från före 0.67.0) **är** det här säljets byte:
     * den kopplas och får säljets likvid i stället för att en andra startas — säljdagen står, så
     * kandidaternas nollpunkt rörs inte. Är den utgången men ännu inte stängd av
     * bakgrundskörningen blockerar den som förut (fond + dag): att starta en ny bredvid hade gett
     * två bevakningar för samma sälj. En bevakning från en annan dag kopplas aldrig utan att
     * användaren tillfrågas (B, #144).
     *
     * @return bevakningens id — den nystartade eller den kopplade — eller null när säljet redan
     *   har en bevakning, när [sale] inte är ett sparat sälj eller när fonden saknar ISIN
     *   (kandidaterna nycklas på ISIN).
     */
    suspend fun startWatchForSale(sale: Transaction, today: LocalDate = LocalDate.now()): Long? {
        if (sale.type != TransactionType.SALJ || sale.id == 0L) return null
        val fund = fundFor(sale)
        val isin = fund?.isin ?: return null
        // Dedupen går på transaktionen; bevakningar med länk matchar bara på den, så två sälj
        // samma dag i samma fond kan få varsin.
        if (switchWatchRepository.hasOpenForTransaction(sale.id)) return null

        val existing = switchWatchRepository.findOpenWithoutTransactionFor(isin, sale.epochDay)
        if (existing != null) {
            if (SwitchWatchCalc.isExpired(existing, today)) return null
            switchWatchRepository.linkSellTransaction(existing.id, sale.id, sale.amount - sale.fee)
            return existing.id
        }

        return switchWatchRepository.start(
            SwitchWatch(
                sellIsin = isin,
                sellFundName = fund.name,
                soldAtEpochDay = sale.epochDay,
                proceedsKr = sale.amount - sale.fee,
                targetLevel = fundMetadataRepository.cachedMetadataFor(listOf(isin))[isin]?.risk,
                sellTransactionId = sale.id,
            ),
        )
    }

    /**
     * Tar bort en transaktion (TRX-2). Repositoryt nollar länken i den bevakning transaktionen
     * startade eller avslutade; bevakningen ligger kvar. Planen räknas inte om (#144).
     */
    suspend fun delete(transactionId: Long) = transactionRepository.deleteTransaction(transactionId)

    /**
     * Startar bevakningen ur ett kvitterat förslag **utan** transaktion — "Bevaka alternativ" på
     * Beslut och Fonddetalj (HEM-8/ANA-10) tills Följ går via formuläret (B, #144). Säljdagen blir
     * [today]: kvitteringen görs när bytet gjorts, och ett påhittat tidigare datum hade mätt
     * kandidaternas utveckling över dagar pengarna inte var fria. Ingen [SwitchWatch.sellTransactionId]
     * — den transaktionen är inte känd här, och ett påhittat id vore ett påstående. Dedupen är
     * därför (fond, dag): en öppen bevakning av säljfonden från [today] blockerar.
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
     * Kvitterar att [boughtIsin] köptes och stänger bevakningen som [SwitchWatchCloseReason.KOPT]
     * (ANA-12) på [today] — "Köpte den här" på bevakningsskärmen, utan transaktion och därmed
     * utan [SwitchWatch.buyTransactionId] tills knappen går via formuläret (#144). Startades
     * bevakningen ur ett förslag markeras förslaget som genomfört (`followed`, SET-5) **bara**
     * när det köpta var den föreslagna fonden — facit mäter följda råd, och ett byte till något
     * annat är inte rådet som följdes.
     *
     * Stängningen är en villkorad uppdatering i databasen (`closedAtEpochDay IS NULL`), och bara
     * anropet vars uppdatering träffade raden kvitterar och returnerar sant — två samtidiga
     * avslut kan därför aldrig skriva över varandra eller kvittera två gånger.
     *
     * @return sant om bevakningen stängdes nu; falskt när den inte finns eller redan var stängd.
     */
    suspend fun markBought(watchId: Long, boughtIsin: String?, today: LocalDate = LocalDate.now()): Boolean {
        val watch = switchWatchRepository.observe(watchId).first() ?: return false
        if (!switchWatchRepository.close(watchId, SwitchWatchCloseReason.KOPT, today, boughtIsin)) return false

        val recordId = watch.sourceRecordId ?: return true
        val record = recordById(recordId)
        if (record != null && boughtIsin != null && record.buyIsin == boughtIsin) {
            suggestionRecordRepository.setFollowed(recordId, true)
        }
        return true
    }

    private suspend fun fundFor(transaction: Transaction): Fund? =
        transactionRepository.observeFunds().first().firstOrNull { it.fundId == transaction.fundId }

    /** Riktad uppslagning — aldrig hela historiken för en rad. */
    private suspend fun recordById(recordId: Long): SuggestionRecord? =
        suggestionRecordRepository.observeByIds(setOf(recordId)).first().firstOrNull()
}
