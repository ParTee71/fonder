package se.partee71.fonder.domain.usecase

import java.time.LocalDate

/** Ren, testbar validering av transaktionsformuläret (issue #4, avgiftsfält issue #10). */
object TransactionFormValidator {

    fun isValid(fundId: String?, shares: Double?, pricePerShare: Double?, date: LocalDate?, fee: Double? = 0.0): Boolean =
        fundId != null &&
            date != null &&
            shares != null && shares > 0.0 &&
            pricePerShare != null && pricePerShare > 0.0 &&
            fee != null && fee >= 0.0
}

/**
 * Förifyllningen av säljformuläret från Följ (BYT-2, issue #144): andelar = förslagets belopp ÷
 * senaste kursen, avrundat till fyra decimaler som bankens avräkningsnotor. Ett förslag i
 * Fonders storlek ger aldrig fler decimaler än så; användaren rättar till det banken bokförde.
 */
object TransactionPrefill {

    const val SHARE_DECIMALS = 4

    /** Null utan belopp eller kurs, eller när de inte ger ett positivt antal — då lämnas fältet tomt. */
    fun sharesForAmount(amountKr: Double?, nav: Double?): Double? {
        if (amountKr == null || nav == null || amountKr <= 0.0 || nav <= 0.0) return null
        return java.math.BigDecimal.valueOf(amountKr / nav).setScale(SHARE_DECIMALS, java.math.RoundingMode.HALF_UP).toDouble()
    }
}
