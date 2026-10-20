package se.partee71.fonder.data.common

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.ui.common.toMessage

/** Den enda felmappningen (NFR-6): datalagrets fel → android-ui:s `UiError`. */
class AppErrorsTest {

    @Test
    fun `ett DataError blir ett UiError med appens text och bara det som inte finns erbjuder inte Försök igen`() {
        listOf(DataError.Database, DataError.Network, DataError.ImportUnreadable, DataError.ImportEmpty, DataError.ImportNoTransactions, DataError.Unknown).forEach { error ->
            val mapped = AppErrors.map(error)
            assertEquals(error, mapped.cause)
            assertEquals(error.toMessage(), mapped.message)
            assertTrue("$error ska gå att försöka igen", mapped.retryable)
        }
        val gone = AppErrors.notFound()
        assertEquals(DataError.NotFound, gone.cause)
        assertEquals(DataError.NotFound.toMessage(), gone.message)
        assertFalse(gone.retryable)
        assertFalse(AppErrors.map(DataError.NotFound).retryable)
    }

    @Test
    fun `ett I-O-fel från en källa blir Network`() {
        assertEquals(DataError.Network, AppErrors.map(IOException("Oväntat svar (503)")).cause)
    }

    @Test
    fun `ett oväntat fel blir Unknown och dess meddelande följer inte med`() {
        val mapped = AppErrors.map(IllegalStateException("Sverige Småbolag 12 345 kr"))
        assertEquals(DataError.Unknown, mapped.cause)
        assertEquals(DataError.Unknown.toMessage(), mapped.message)
        assertFalse("Småbolag" in mapped.toString())
    }

    @Test
    fun `samma fel två gånger är två olika händelser`() {
        assertNotSame(AppErrors.map(DataError.Database), AppErrors.map(DataError.Database))
    }
}
