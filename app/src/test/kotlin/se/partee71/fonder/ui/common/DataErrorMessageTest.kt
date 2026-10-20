package se.partee71.fonder.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import se.partee71.fonder.data.common.DataError

/** `DataError.toMessage()` – den enda översättningen från fel till svensk text (NFR-6). */
@RunWith(RobolectricTestRunner::class)
class DataErrorMessageTest {

    private val all = listOf(
        DataError.Database,
        DataError.Network,
        DataError.ImportUnreadable,
        DataError.ImportEmpty,
        DataError.ImportNoTransactions,
        DataError.SignInNoCredential,
        DataError.SignInFailed,
        DataError.BackupUnsupportedVersion,
        DataError.BackupUnreadable,
        DataError.BackupExportFailed,
        DataError.DriveSignedOut,
        DataError.DriveFailed,
        DataError.NotFound,
        DataError.Unknown
    )

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `varje fel har en egen svensk text`() {
        val texts = all.map { context.getString(it.toMessage()) }
        assertEquals("två fel delar text: $texts", all.size, texts.toSet().size)
        assertTrue(texts.all { it.isNotBlank() })
        assertEquals("Något gick fel. Försök igen.", context.getString(DataError.Unknown.toMessage()))
    }

    @Test
    fun `importfelen behåller importskärmarnas texter`() {
        assertEquals("Kunde inte läsa filen. Kontrollera att det är en giltig Handelsbanken-export.", context.getString(DataError.ImportUnreadable.toMessage()))
        assertEquals("Inga rader hittades i filen.", context.getString(DataError.ImportEmpty.toMessage()))
        assertEquals(
            "Kunde inte läsa någon transaktion ur de valda filerna. Kontrollera att det är Handelsbankens avräkningsnotor (PDF).",
            context.getString(DataError.ImportNoTransactions.toMessage())
        )
    }

    @Test
    fun `inloggningen, sakerhetskopian och Drive behaller Installningars texter`() {
        mapOf(
            DataError.SignInNoCredential to se.partee71.fonder.R.string.sign_in_failed_no_credential,
            DataError.SignInFailed to se.partee71.fonder.R.string.sign_in_failed,
            DataError.BackupUnsupportedVersion to se.partee71.fonder.R.string.backup_restore_failed_version,
            DataError.BackupUnreadable to se.partee71.fonder.R.string.backup_restore_failed_unreadable,
            DataError.BackupExportFailed to se.partee71.fonder.R.string.backup_export_failed,
            DataError.DriveSignedOut to se.partee71.fonder.R.string.drive_backup_signed_out,
            DataError.DriveFailed to se.partee71.fonder.R.string.drive_backup_failed
        ).forEach { (error, text) -> assertEquals(error.toString(), text, error.toMessage()) }
    }
}
