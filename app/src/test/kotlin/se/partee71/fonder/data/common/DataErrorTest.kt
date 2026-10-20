package se.partee71.fonder.data.common

import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
}
