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

    /** Flest decimaler ett antal andelar visas med — fler än bankernas avräkningsnotor har, så hela innehavet får plats. */
    const val SHARE_MAX_DECIMALS = 6

    /**
     * Ett antal andelar med alla sina decimaler (högst [SHARE_MAX_DECIMALS], nollor på slutet
     * strukna) och decimalkomma, utan tusentalsavgränsare — redigerbart och tolkas tillbaka av
     * [parse] ("37,5214"). Avrundningen tar bort flyttalsbrus från summerade köp och sälj.
     */
    fun formatShares(value: Double): String = java.math.BigDecimal.valueOf(value)
        .setScale(SHARE_MAX_DECIMALS, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .let { if (it.scale() < 0) it.setScale(0) else it }
        .toPlainString()
        .replace('.', ',')
}
