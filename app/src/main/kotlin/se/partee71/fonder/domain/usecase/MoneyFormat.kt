package se.partee71.fonder.domain.usecase

import java.util.Locale

/**
 * Ren, testbar formatering av belopp och avkastning på svenska.
 * Ingen Android-beroende → körs som JVM-enhetstest.
 */
object MoneyFormat {

    private val locale = Locale("sv", "SE")

    /** Formaterar ett kronbelopp, t.ex. 1234.5 → "1 234,50 kr". */
    fun kr(amount: Double): String =
        String.format(locale, "%,.2f kr", amount)

    /** Formaterar en avkastning med tecken, t.ex. 0.1234 → "+12,3 %", -0.05 → "−5,0 %". */
    fun percentSigned(fraction: Double): String {
        val pct = fraction * 100.0
        val sign = if (pct >= 0.0) "+" else "−" // äkta minustecken
        return String.format(locale, "%s%.1f %%", sign, kotlin.math.abs(pct))
    }

    /** Formaterar en osignerad procentandel, t.ex. 0.182 → "18,2 %" — för mått som aldrig är negativa (volatilitet). */
    fun percent(fraction: Double): String =
        String.format(locale, "%.1f %%", fraction * 100.0)

    /** Formaterar ett rent decimaltal med två decimaler och äkta minustecken, t.ex. 0.83 → "0,83", -0.4 → "−0,40" (Sharpe-kvot). */
    fun decimal(value: Double): String {
        val sign = if (value < 0.0) "−" else "" // äkta minustecken
        return String.format(locale, "%s%.2f", sign, kotlin.math.abs(value))
    }

    /**
     * Formaterar en fondavgift med två decimaler, t.ex. 0.73 → "0,73 %" (issue #59). Till
     * skillnad från [percent], som tar emot en *fraction* (0..1), är källans `totalFee`
     * redan en procentsiffra (0,73 betyder 0,73 %, inte 73 %) — en egen formatterare
     * undviker att den skillnaden blandas ihop. Två decimaler i stället för [percent]s en,
     * eftersom avgiftsskillnader ofta ligger under en tiondels procentenhet (0,73 % mot
     * 0,21 % — en decimal hade avrundat bort just den skillnaden).
     */
    fun feePercent(totalFeePercent: Double): String =
        String.format(locale, "%.2f %%", totalFeePercent)
}
