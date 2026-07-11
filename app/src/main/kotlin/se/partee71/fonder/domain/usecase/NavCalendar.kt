package se.partee71.fonder.domain.usecase

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Vilket datums fond-NAV vi **borde** ha, givet att en fond-NAV sätts högst en gång per
 * handelsdag (kväll efter börsstängning) — kärnan i den handelsdagsmedvetna kursuppdateringen
 * (issue #27, TP-17). Ersätter den tidigare "senaste kurs < idag"-jämförelsen, som var fel på
 * helger (försökte hämta trots att fredagens NAV redan var det senaste som fanns) och på
 * kvällar (sa "färskt" fast dagens NAV redan kommit). Ren/testbar, inget Android-beroende.
 */
object NavCalendar {

    /**
     * Ungefärlig klockslag på kvällen då svenska fond-NAV:er brukar vara publicerade för
     * dagen — en approximation (v1, ej per fondbolag), lätt att justera vid behov.
     */
    private const val PUBLISH_HOUR = 18

    /**
     * @return senaste handelsdagen vars NAV borde vara känd, givet [now]: helg → senaste
     *   vardagen (fredag), vardag före [PUBLISH_HOUR] → föregående vardag, annars dagens datum.
     */
    fun expectedLatestNavDay(now: LocalDateTime): LocalDate {
        val today = now.toLocalDate()
        return when {
            today.dayOfWeek.isWeekend -> latestWeekdayOnOrBefore(today)
            now.hour < PUBLISH_HOUR -> previousWeekday(today)
            else -> today
        }
    }

    private val DayOfWeek.isWeekend: Boolean
        get() = this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY

    private fun latestWeekdayOnOrBefore(date: LocalDate): LocalDate {
        var d = date
        while (d.dayOfWeek.isWeekend) d = d.minusDays(1)
        return d
    }

    private fun previousWeekday(date: LocalDate): LocalDate =
        latestWeekdayOnOrBefore(date.minusDays(1))
}
