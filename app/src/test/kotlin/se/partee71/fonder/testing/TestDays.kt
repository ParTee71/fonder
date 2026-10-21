package se.partee71.fonder.testing

import java.time.LocalDate
import org.junit.Assert.assertTrue

/**
 * Dagen ett test startar, för påståenden om datum som koden under test räknar fram med sitt eget
 * `LocalDate.now()` (ViewModels och workern har ingen injicerbar klocka). Passerar testet midnatt
 * kan koden ha räknat med startdagen eller med dagen därpå – [assertForTestDay] godtar exakt de
 * två, så testet blir deterministiskt utan att bli slappare än en dag.
 */
class TestDays {
    /** Testets startdag – använd den för att bygga fixturerna. */
    val today: LocalDate = LocalDate.now()

    /** Startdagen och, om midnatt passerats, dagen då påståendet görs. */
    fun candidates(): List<LocalDate> = listOf(today, LocalDate.now()).distinct()

    /** [actual] ska vara [expected] räknat för någon av [candidates]. */
    fun <T> assertForTestDay(actual: T, expected: (LocalDate) -> T) {
        val allowed = candidates().map(expected)
        assertTrue("väntade något av $allowed, fick $actual", actual in allowed)
    }
}
