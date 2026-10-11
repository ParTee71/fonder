package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import se.partee71.fonder.domain.model.BackupRecord
import se.partee71.fonder.domain.model.BackupTarget
import java.time.ZoneOffset

/** HEM-12: när Hem påminner om att säkerhetskopiera, och vad raden säger. */
class BackupReminderCalcTest {

    private val current = BackupContentState(fingerprint = "abc", transactionCount = 5, hasData = true)
    private fun record(fingerprint: String?, count: Int?) =
        BackupRecord(epochMillis = 1_700_000_000_000L, target = BackupTarget.DRIVE, fingerprint = fingerprint, transactionCount = count)

    @Test
    fun `utan data paminns inget, inte ens utan kopia`() {
        assertNull(BackupReminderCalc.reminder(current.copy(hasData = false), last = null))
    }

    @Test
    fun `data utan kopia ger en paminnelse utan datum`() {
        assertEquals(BackupReminder(0, null), BackupReminderCalc.reminder(current, last = null))
    }

    @Test
    fun `samma innehall som kopian ger ingen paminnelse`() {
        assertNull(BackupReminderCalc.reminder(current, record("abc", 5)))
    }

    @Test
    fun `andrat innehall raknar nya transaktioner`() {
        assertEquals(BackupReminder(2, 1_700_000_000_000L), BackupReminderCalc.reminder(current, record("old", 3)))
    }

    @Test
    fun `andrat innehall utan fler transaktioner visar bara datumet`() {
        assertEquals(BackupReminder(0, 1_700_000_000_000L), BackupReminderCalc.reminder(current, record("old", 7)))
    }

    @Test
    fun `en kopia fran fore paminnelsen raknas som inaktuell`() {
        assertEquals(BackupReminder(0, 1_700_000_000_000L), BackupReminderCalc.reminder(current, record(null, null)))
    }

    @Test
    fun `datumet visas som appens ovriga datum`() {
        assertEquals("2023-11-14", BackupReminderCalc.formatDate(1_700_000_000_000L, ZoneOffset.UTC))
    }
}
