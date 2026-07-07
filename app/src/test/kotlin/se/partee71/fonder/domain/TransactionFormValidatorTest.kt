package se.partee71.fonder.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.usecase.TransactionFormValidator
import java.time.LocalDate

class TransactionFormValidatorTest {

    private val date = LocalDate.of(2026, 7, 1)

    @Test
    fun `giltig nar allt ar ifyllt och positivt`() {
        assertTrue(TransactionFormValidator.isValid("SHB0000442", 1.0, 100.0, date))
    }

    @Test
    fun `ogiltig utan vald fond`() {
        assertFalse(TransactionFormValidator.isValid(null, 1.0, 100.0, date))
    }

    @Test
    fun `ogiltig utan datum`() {
        assertFalse(TransactionFormValidator.isValid("SHB0000442", 1.0, 100.0, null))
    }

    @Test
    fun `ogiltig med noll eller negativt antal`() {
        assertFalse(TransactionFormValidator.isValid("SHB0000442", 0.0, 100.0, date))
        assertFalse(TransactionFormValidator.isValid("SHB0000442", -1.0, 100.0, date))
        assertFalse(TransactionFormValidator.isValid("SHB0000442", null, 100.0, date))
    }

    @Test
    fun `ogiltig med noll eller negativ kurs`() {
        assertFalse(TransactionFormValidator.isValid("SHB0000442", 1.0, 0.0, date))
        assertFalse(TransactionFormValidator.isValid("SHB0000442", 1.0, -5.0, date))
        assertFalse(TransactionFormValidator.isValid("SHB0000442", 1.0, null, date))
    }

    @Test
    fun `giltig med noll avgift (default) eller ospecificerad avgift`() {
        assertTrue(TransactionFormValidator.isValid("SHB0000442", 1.0, 100.0, date, fee = 0.0))
        assertTrue(TransactionFormValidator.isValid("SHB0000442", 1.0, 100.0, date))
    }

    @Test
    fun `giltig med positiv avgift`() {
        assertTrue(TransactionFormValidator.isValid("SHB0000442", 1.0, 100.0, date, fee = 25.0))
    }

    @Test
    fun `ogiltig med negativ eller okand avgift`() {
        assertFalse(TransactionFormValidator.isValid("SHB0000442", 1.0, 100.0, date, fee = -1.0))
        assertFalse(TransactionFormValidator.isValid("SHB0000442", 1.0, 100.0, date, fee = null))
    }
}
