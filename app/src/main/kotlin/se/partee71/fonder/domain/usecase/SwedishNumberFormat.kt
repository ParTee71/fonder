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
}
