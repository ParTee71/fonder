package se.partee71.fonder.domain.usecase

import java.time.LocalDate

/**
 * Åldersgräns för en fonds **köpbarhet hos Handelsbanken**
 * ([se.partee71.fonder.data.repository.FundMetadataRepository.resolveHandelsbankenAvailability],
 * KRAVLISTA TP-21) — till skillnad från själva fondmetadatan (avgift, kategori), som alltid
 * skrivs över av nästa livehämtning och därför inte behöver en egen TTL, ändras Handelsbankens
 * utbud över tid (en fond kan sluta säljas där, eller tillkomma) utan att det syns i Avanzas
 * fondmetadata. Ett gammalt "köpbar"/"inte köpbar"-svar riskerar därför att bli fel på ett sätt
 * ren metadata-cache inte gör — därav den separata, glesa TTL:en.
 */
object FundMetadataFreshness {

    /** Avgifter och kategorier ändras sällan — men Handelsbankens utbud kan, därav 30 dygn. */
    const val AVAILABILITY_TTL_DAYS = 30L

    fun isStale(resolvedAtEpochDay: Long, today: LocalDate, ttlDays: Long = AVAILABILITY_TTL_DAYS): Boolean =
        resolvedAtEpochDay < today.toEpochDay() - ttlDays
}
