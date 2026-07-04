package se.partee71.fonder.domain.usecase

/** Delad tolkning av svenskt talformat (kommatecken som decimalavgränsare, mellanslag som tusentalsavgränsare). */
object SwedishNumberFormat {

    fun parse(raw: String): Double? {
        val cleaned = raw.trim()
            .replace(" ", "")
            .replace(" ", "")
            .replace(",", ".")
        return cleaned.toDoubleOrNull()
    }
}
