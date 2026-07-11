package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class NavCalendarTest {

    @Test
    fun `lordag ger senaste fredagen`() {
        val saturday = LocalDateTime.of(2026, 7, 11, 12, 0) // lördag
        assertEquals(LocalDate.of(2026, 7, 10), NavCalendar.expectedLatestNavDay(saturday))
    }

    @Test
    fun `sondag ger senaste fredagen`() {
        val sunday = LocalDateTime.of(2026, 7, 12, 9, 0) // söndag
        assertEquals(LocalDate.of(2026, 7, 10), NavCalendar.expectedLatestNavDay(sunday))
    }

    @Test
    fun `mandag morgon fore publicering ger fredagen (inte sondag)`() {
        val mondayMorning = LocalDateTime.of(2026, 7, 13, 8, 0) // måndag, 08:00
        assertEquals(LocalDate.of(2026, 7, 10), NavCalendar.expectedLatestNavDay(mondayMorning))
    }

    @Test
    fun `mandag kvall efter publicering ger mandagen`() {
        val mondayEvening = LocalDateTime.of(2026, 7, 13, 19, 0) // måndag, 19:00
        assertEquals(LocalDate.of(2026, 7, 13), NavCalendar.expectedLatestNavDay(mondayEvening))
    }

    @Test
    fun `vardag fore publicering ger foregaende vardag`() {
        val wednesdayMorning = LocalDateTime.of(2026, 7, 15, 10, 0) // onsdag, 10:00
        assertEquals(LocalDate.of(2026, 7, 14), NavCalendar.expectedLatestNavDay(wednesdayMorning)) // tisdag
    }

    @Test
    fun `vardag exakt vid publiceringstimmen raknas som efter`() {
        val exactlyAtPublishHour = LocalDateTime.of(2026, 7, 15, 18, 0) // onsdag, 18:00
        assertEquals(LocalDate.of(2026, 7, 15), NavCalendar.expectedLatestNavDay(exactlyAtPublishHour))
    }

    @Test
    fun `vardag en minut fore publiceringstimmen raknas som fore`() {
        val justBeforePublishHour = LocalDateTime.of(2026, 7, 15, 17, 59) // onsdag, 17:59
        assertEquals(LocalDate.of(2026, 7, 14), NavCalendar.expectedLatestNavDay(justBeforePublishHour))
    }
}
