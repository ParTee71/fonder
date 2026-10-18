package se.partee71.fonder.data.repository

import java.security.MessageDigest
import se.partee71.fonder.domain.model.SwitchWatch
import se.partee71.fonder.domain.model.SwitchWatchCandidate
import se.partee71.fonder.domain.model.SwitchWatchCandidateSource
import se.partee71.fonder.domain.model.SwitchWatchCloseReason

/**
 * Fingeravtryck av backup-kontraktets innehåll (HEM-12) — samma innehåll ger samma sträng, en
 * ändring som en säkerhetskopia skulle fånga ger en annan.
 *
 * SHA-256 över [BackupSerializer]s egen kodning av en **normaliserad** [BackupPayload], så
 * fingeravtrycket följer formatet automatiskt när ett fält läggs till. Normaliseringen tar bort
 * det som ändras utan att användarens data gör det — annars hade Hem påmint efter varje
 * bakgrundskörning:
 *
 * - `exportedAtEpochMillis` — tidpunkten för läsningen, inte data.
 * - `Fund.fondlistaFundId` — en källnyckel bakgrundsuppdateringen slår upp och sparar själv.
 * - Inspelade förslag som användaren inte kvitterat (`followed == null`) — bakgrundsskanningen
 *   (HEM-8) spelar in och gallrar dem på egen hand. Kvitterade förslag är användarens val och
 *   räknas.
 * - Bytesbevakningarnas **automatiska** kandidater (`SwitchWatchCandidateSource.AUTO`, ANA-13) —
 *   de fylls på och byts ut av appen. Kvar blir bara de kandidater användaren själv lagt till
 *   (`MANUELL`), och för dem bara vilken fond det är: id, position och nollpunkten
 *   (`navAtStart`/`navAtStartEpochDay`) ankras och numreras av appen, och id:n byts dessutom vid
 *   en återställning. Att användaren tar bort en automatisk kandidat syns därför inte — det är
 *   priset för att omfyllningen inte ska ge en påminnelse.
 * - En bevakning som **gått ut** (`UTGANGEN`) stängs av bakgrundskörningen och räknas som öppen;
 *   användarens egna stängningar (köpt, avbruten) räknas.
 *
 * Avståendena (HEM-14) är användarens val och räknas oförändrade.
 *
 * Listorna sorteras, eftersom Room inte lovar någon ordning utan `ORDER BY`.
 */
object BackupFingerprint {

    fun of(payload: BackupPayload): String {
        val normalized = payload.copy(
            exportedAtEpochMillis = 0,
            funds = payload.funds.map { it.copy(fondlistaFundId = null) }.sortedBy { it.fundId },
            transactions = payload.transactions.sortedBy { it.id },
            suggestionRecords = payload.suggestionRecords.filter { it.followed != null }.sortedBy { it.id },
            switchWatches = payload.switchWatches.map(::userPart).sortedBy { it.id },
            suggestionDismissals = payload.suggestionDismissals.sortedWith(compareBy({ it.sellIsin }, { it.buyIsin }, { it.kind }))
        )
        val digest = MessageDigest.getInstance("SHA-256").digest(BackupSerializer.encode(normalized).toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Det i en bevakning som användaren själv bestämt — se klassens KDoc. */
    private fun userPart(watch: SwitchWatch): SwitchWatch {
        val expired = watch.closeReason == SwitchWatchCloseReason.UTGANGEN
        return watch.copy(
            closedAtEpochDay = if (expired) null else watch.closedAtEpochDay,
            closeReason = if (expired) null else watch.closeReason,
            candidates = watch.candidates
                .filter { it.source == SwitchWatchCandidateSource.MANUELL }
                .map { SwitchWatchCandidate(isin = it.isin, name = it.name, source = it.source) }
                .sortedBy { it.isin }
        )
    }
}
