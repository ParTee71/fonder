package se.partee71.fonder.domain.usecase

import java.time.LocalDate

/**
 * Åldersgränser för cachad fondmetadata (KRAVLISTA TP-21).
 *
 * [AVAILABILITY_TTL_DAYS] gäller en fonds **köpbarhet hos Handelsbanken**
 * ([se.partee71.fonder.data.repository.FundMetadataRepository.resolveHandelsbankenAvailability]):
 * Handelsbankens utbud ändras över tid (en fond kan sluta säljas där, eller tillkomma) utan
 * att det syns i Avanzas fondmetadata, så ett gammalt "köpbar"/"inte köpbar"-svar riskerar att
 * bli fel på ett sätt ren avgiftsdata inte gör.
 *
 * [FEE_TTL_DAYS] gäller själva fondmetadatan (avgift, kategori). En fråga mot källan
 * ([se.partee71.fonder.data.repository.FundMetadataRepository.query]) skriver alltid över
 * cachen med det färska svaret, så den behöver ingen egen TTL där. Men en ren
 * cache-först-läsning (`metadataFor`, issue #60) gör aldrig en sådan livehämtning av sig
 * själv — utan en egen TTL skulle en avgiftstotal kunna byggas på en godtyckligt gammal
 * avgift utan att någon märkte det.
 *
 * [COMPARISON_TTL_DAYS] gäller den persisterade jämförelsen mot billigare alternativ
 * (ANA-9/HEM-6, issue #61). Ett gammalt resultat är inte harmlöst på samma sätt som gammal
 * avgiftsdata: rekommenderar appen en fond som sedan höjt sin avgift eller slutat säljas hos
 * Handelsbanken är rådet direkt fel, inte bara inaktuellt. Ett utgånget resultat räknas
 * därför som osökt (samma som `comparisonResolvedAtEpochDay == null`) i stället för att visas
 * som en aktuell rekommendation.
 */
object FundMetadataFreshness {

    /** Handelsbankens utbud kan ändras utan att synas i Avanzas metadata, därav 30 dygn. */
    const val AVAILABILITY_TTL_DAYS = 30L

    /** Avgifter och kategorier ändras sällan — samma 30 dygn som [AVAILABILITY_TTL_DAYS], tills något talar emot. */
    const val FEE_TTL_DAYS = 30L

    /** Samma 30 dygn som [AVAILABILITY_TTL_DAYS] — jämförelsen vilar ändå på köpbarheten. */
    const val COMPARISON_TTL_DAYS = 30L

    fun isStale(resolvedAtEpochDay: Long, today: LocalDate, ttlDays: Long = AVAILABILITY_TTL_DAYS): Boolean =
        resolvedAtEpochDay < today.toEpochDay() - ttlDays
}
