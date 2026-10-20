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
}
