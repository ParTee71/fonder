package se.partee71.fonder.domain.usecase

import java.time.LocalDate

/** Ren, testbar validering av transaktionsformuläret (issue #4). */
object TransactionFormValidator {

    fun isValid(fundId: String?, shares: Double?, pricePerShare: Double?, date: LocalDate?): Boolean =
        fundId != null &&
            date != null &&
            shares != null && shares > 0.0 &&
            pricePerShare != null && pricePerShare > 0.0
}
