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
}
