package se.partee71.fonder.domain.usecase

/**
 * Normaliserad nyckel för ett fondnamn — används för att koppla ihop en fond från
 * fondlista-katalogen (som saknar ISIN i katalogsvaret, se
 * [se.partee71.fonder.data.network.HandelsbankenHtmlParser.parseFundCatalog]) med cachad
 * fondmetadata från den ISIN-baserade källan, så risknivån kan visas i fondsök (UI-10, issue #85).
 *
 * Medvetet **exakt** matchning på normaliserat namn, inte den ordbaserade likheten i
 * [FundNameMatcher]: där är en trolig träff bättre än ingen, eftersom användaren bekräftar den
 * innan något sparas (IMP-2). Här skulle en felaktig träff i stället tyst måla en risksiffra
 * från *en annan fond* på en söklista-rad, utan något bekräftelsesteg — och en risknivå som ser
 * exakt lika trovärdig ut som en riktig är sämre än ingen risknivå alls (ANA-4-principen).
 * Namn utan träff visas därför som okänd risk.
 *
 * Normaliseringen tål bara skillnader som är rent kosmetiska: versaler/gemener, extra blanksteg
 * och skiljetecken ("Handelsbanken Sverige Index (A1 SEK)" ↔ "handelsbanken sverige index a1 sek").
 */
object FundNameKey {

    private val nonAlphanumeric = Regex("[^\\p{L}\\p{N}]+")

    /** Normaliserad nyckel för [name] — tom sträng om namnet saknar tecken att matcha på. */
    fun of(name: String): String =
        name.lowercase().replace(nonAlphanumeric, " ").trim().replace(Regex("\\s+"), " ")
}
