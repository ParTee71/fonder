package se.partee71.fonder.domain.usecase

/** Delad tolkning av svenskt talformat (kommatecken som decimalavgränsare, mellanslag som tusentalsavgränsare). */
object SwedishNumberFormat {

    /** Icke-brytande mellanslag (U+00A0) — vanligt i talformatering på webbsidor, t.ex. Handelsbankens kurstabell. */
    private const val NON_BREAKING_SPACE = ' '

    fun parse(raw: String): Double? {
        val cleaned = raw.trim()
            .replace(" ", "")
            .replace(NON_BREAKING_SPACE.toString(), "")
            .replace(",", ".")
        return cleaned.toDoubleOrNull()
    }

    /**
     * [value] med exakt [decimals] decimaler och decimalkomma, utan tusentalsavgränsare — så
     * texten går att redigera och tolkas tillbaka av [parse] ("38,4615").
     */
    fun format(value: Double, decimals: Int): String = java.math.BigDecimal.valueOf(value).setScale(decimals, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',')
}
