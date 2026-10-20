package se.partee71.fonder.data.common

import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.data.auth.SignInException
import se.partee71.fonder.data.repository.BackupFormatException
import se.partee71.fonder.data.repository.DriveResult

/**
 * [DataError.of] – Room-felen i JVM kräver Robolectric, eftersom SQLite-undantagen kommer från
 * Android-plattformen (NFR-6).
 */
@RunWith(RobolectricTestRunner::class)
class DataErrorTest {

    @Test
    fun `ett SQLite-fel från Room blir Database`() {
        listOf(SQLiteException("no such table: funds"), SQLiteFullException(), SQLiteDiskIOException()).forEach {
            assertEquals(DataError.Database, DataError.of(it))
        }
    }

    @Test
    fun `ett I-O-fel blir Network och allt annat Unknown`() {
        assertEquals(DataError.Network, DataError.of(IOException()))
        assertEquals(DataError.Unknown, DataError.of(IllegalArgumentException()))
    }

    @Test
    fun `ett redan mappat fel behålls`() {
        assertSame(DataError.ImportEmpty, DataError.of(DataError.ImportEmpty))
    }

    @Test
    fun `sakerhetskopians och inloggningens fel blir sin orsak`() {
        assertEquals(DataError.BackupUnsupportedVersion, DataError.of(BackupFormatException(BackupFormatException.Reason.UNSUPPORTED_VERSION, fileVersion = 99)))
        assertEquals(DataError.BackupUnreadable, DataError.of(BackupFormatException(BackupFormatException.Reason.UNREADABLE)))
        assertEquals(DataError.SignInNoCredential, DataError.of(SignInException(SignInException.Reason.NO_CREDENTIAL)))
        assertEquals(DataError.SignInFailed, DataError.of(SignInException(SignInException.Reason.FAILED)))
    }

    @Test
    fun `Drives svar blir ett fel bara nar det ar ett`() {
        assertEquals(DataError.DriveSignedOut, DataError.of(DriveResult.NoAccount))
        assertEquals(DataError.DriveFailed, DataError.of(DriveResult.Error("Drive 503")))
        assertNull(DataError.of(DriveResult.Success("{}")))
        assertNull(DataError.of(DriveResult.NoBackupFound))
    }

    @Test
    fun `suspendRunCatching ger felet som DataError och slapper igenom avbrott`() = runTest {
        assertEquals(DataError.Network, suspendRunCatching { throw IOException() }.exceptionOrNull())
        assertEquals(DataError.Unknown, suspendRunCatching({ DataError.Unknown }) { throw IOException() }.exceptionOrNull())
        assertEquals(1, suspendRunCatching { 1 }.getOrNull())
        assertThrows(CancellationException::class.java) { kotlinx.coroutines.runBlocking { suspendRunCatching { throw CancellationException() } } }
    }
}
