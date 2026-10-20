package se.partee71.fonder.ui.common

import app.cash.turbine.test
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.data.common.DataError
import se.partee71.ui.common.ListUiState
import se.partee71.ui.common.UiError
import se.partee71.ui.common.toListUiState

/** Fonders hjälpare kring android-ui:s [ListUiState] för listskärmar med mer än listan i tillståndet (NFR-6). */
class ListStatesTest {

    @Test
    fun `raderna finns bara i innehållsläget`() {
        assertEquals(listOf("a", "b"), listOf("a", "b").toListUiState().itemsOrEmpty())
        assertTrue(ListUiState.Loading.itemsOrEmpty().isEmpty())
        assertTrue(ListUiState.Empty.itemsOrEmpty().isEmpty())
        assertTrue(ListUiState.Error(UiError(DataError.Unknown, 0)).itemsOrEmpty().isEmpty())
    }

    @Test
    fun `felet finns bara i felläget`() {
        val error = UiError(DataError.Database, 0)
        assertSame(error, ListUiState.Error(error).errorOrNull())
        assertNull(ListUiState.Loading.errorOrNull())
        assertNull(ListUiState.Empty.errorOrNull())
        assertNull(listOf(1).toListUiState().errorOrNull())
    }

    @Test
    fun `ett fel i källan blir skärmens tillstånd med ett mappat fel i stället för en krasch`() = runTest {
        val source = flow<String> {
            emit("laddad")
            throw DataError.Database
        }

        source.catchAsListError { "fel: ${it.error.cause}" }.test {
            assertEquals("laddad", awaitItem())
            assertEquals("fel: ${DataError.Database}", awaitItem())
            awaitComplete()
        }
    }
}
