package se.partee71.fonder.domain.usecase

import se.partee71.fonder.domain.model.BackupRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Påminnelsen överst på Hem (HEM-12): data har ändrats sedan senaste säkerhetskopian, eller
 * ingen kopia finns alls.
 *
 * @param newTransactions hur många transaktioner som tillkommit sedan kopian, 0 när antalet inte
 *   ökat (eller inte är känt) — raden visar då bara datumet.
 * @param lastBackupEpochMillis när senaste kopian togs, null om ingen finns.
 */
data class BackupReminder(
    val newTransactions: Int,
    val lastBackupEpochMillis: Long?,
)

/** Det som räknas fram ur dagens innehåll — se [BackupReminderCalc.reminder]. */
data class BackupContentState(
    val fingerprint: String,
    val transactionCount: Int,
    /** Finns det något alls att förlora — en fond eller en transaktion. */
    val hasData: Boolean,
)

object BackupReminderCalc {

    /**
     * Null = ingen påminnelse. Utan data finns inget att säkerhetskopiera; utan kopia påminns
     * alltid; annars avgör fingeravtrycket. En kopia utan fingeravtryck (från före HEM-12)
     * räknas som inaktuell.
     */
    fun reminder(current: BackupContentState, last: BackupRecord?): BackupReminder? {
        if (!current.hasData) return null
        if (last == null) return BackupReminder(newTransactions = 0, lastBackupEpochMillis = null)
        if (last.fingerprint == current.fingerprint) return null
        val added = last.transactionCount?.let { current.transactionCount - it } ?: 0
        return BackupReminder(newTransactions = added.coerceAtLeast(0), lastBackupEpochMillis = last.epochMillis)
    }

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** Datumet för en kopia som det visas på Hem och i Inställningar — samma format som appens övriga datum. */
    fun formatDate(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(epochMillis).atZone(zone).format(dateFormat)
}
