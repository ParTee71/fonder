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
