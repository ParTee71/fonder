package se.partee71.fonder.domain.model

/** Var en säkerhetskopia skrevs eller lästes ifrån (SET-6 fil, SET-7 Google Drive). */
enum class BackupTarget { DRIVE, FILE }

/**
 * Den senaste lyckade säkerhetskopian på **den här enheten** (SET-7, HEM-12) — eller den
 * senaste återställningen, som också gör enhetens data lika med en kopia.
 *
 * Enhetsmetadata, inte användardata: ingår medvetet inte i backup-kontraktet (NFR-1), samma
 * kategori som `lastPriceSyncEpochMillis`. En återställd kopia från en annan telefon ska inte ta
 * med sig den telefonens "senast säkerhetskopierad".
 *
 * @param target null bara för en kopia från före HEM-12, då bara Drive-tidsstämpeln sparades.
 * @param fingerprint [se.partee71.fonder.data.repository.BackupFingerprint] för innehållet då;
 *   null för en kopia från före HEM-12 — Hem påminner då tills nästa kopia tagits.
 * @param transactionCount antalet transaktioner då, null av samma skäl.
 */
data class BackupRecord(
    val epochMillis: Long,
    val target: BackupTarget?,
    val fingerprint: String?,
    val transactionCount: Int?
)
