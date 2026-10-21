package se.partee71.fonder.domain.usecase

import java.time.LocalDate

/** Ren, testbar validering av transaktionsformuläret (issue #4, avgiftsfält issue #10). */
object TransactionFormValidator {

    /**
     * Hur mycket ett sälj får gå över innehavet: halva den sista decimalen i en förifylld andel
     * ([TransactionPrefill.SHARE_DECIMALS]) — andelar ur ett belopp som är hela innehavet kan
     * avrundas upp så mycket, och det är inget översälj.
     */
    const val HOLDING_TOLERANCE = 0.5e-4

    fun isValid(fundId: String?, shares: Double?, pricePerShare: Double?, date: LocalDate?, fee: Double? = 0.0): Boolean = fundId != null &&
        date != null &&
        isValidShares(shares) &&
        isValidPrice(pricePerShare) &&
        isValidFee(fee)

    fun isValidShares(shares: Double?): Boolean = shares != null && shares > 0.0

    fun isValidPrice(pricePerShare: Double?): Boolean = pricePerShare != null && pricePerShare > 0.0

    fun isValidFee(fee: Double?): Boolean = fee != null && fee >= 0.0

    /** Ett sälj av [shares] andelar är fler än de [owned] som ägs (TRX-5) — inom [HOLDING_TOLERANCE] är det hela innehavet. */
    fun exceedsHolding(shares: Double, owned: Double): Boolean = shares > owned + HOLDING_TOLERANCE
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
