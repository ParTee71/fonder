package se.partee71.fonder.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.TransactionType
import java.time.LocalDate

/**
 * Fixturen nedan är den textextraktion som faktiskt kunde göras av en riktig Handelsbanken-
 * avräkningsnota (PDF, verifierad av användaren 2026-07-05 — se KRAVLISTA-changelog).
 * PdfBox-Androids [PDFTextStripper][com.tom_roush.pdfbox.text.PDFTextStripper] kan i
 * praktiken bryta rader något annorlunda — se risknotis i KRAVLISTA.
 */
private val REAL_AVRAKNINGSNOTA = """
    Karlskrona
    Box 285
    371 24 Karlskrona
    Mats Nilsson
    Skepparegatan 7 Lgh 1103
    371 30 Karlskrona
    2020-03-16
    AVRÄKNINGSNOTA
    1 (1)
    Svenska Handelsbanken AB (pub) Postadress: Gatuadress: Telefon: Bankgiro:
    Styrelsens säte: Stockholm Box 285 Skeppsbrokajen 8 +46 (0)455 36 74 00 861-3010
    Organisationsnr: 502007-7862 371 24 Karlskrona Telefax:
    Clearing nr: 6741 karlskrona@handelsbanken.se ()
    www.handelsbanken.se/karlskrona
    Nordea Fonder AB
    Småbolagsfond Sverige
    ISIN: SE0003653302
    Ingår i Investeringssparkonto 2 020 387 301
    Fondkontonr : 856 584 525
    Kundnummer : 710108-2910
    Text Belopp Kurs Andelar Saldo andelar
    Ingående saldo 2020-03-13 0.0000
    In Självbetjäning 2020-03-13 5 000.00 294.36 16.9862 16.9862
    Uttaget från konto: 596 961 901
    Fondorder mottagen 2020-03-12 kl. 20:25
    Marknadsvärde 2020-03-16 5 558.73 327.25 16.9862
""".trimIndent()

/**
 * Textextraktion från en riktig avräkningsnota för en FULLSTÄNDIG FÖRSÄLJNING (verifierad
 * av användaren 2026-07-07 — se issue #10). Skiljer sig från köp-fixturen ovan på två sätt
 * som inte var kända förrän nu: transaktionsraden börjar med "Avslut" i stället för "Ut",
 * och belopp/andelar är negativa (minskar saldot till 0.0000). Innehåller också extra
 * rader (Utbetalt belopp/Omkostnadsbelopp/Kapitalvinst) som saknas på en köp-nota.
 */
private val REAL_AVRAKNINGSNOTA_SALJ = """
    Karlskrona
    Box 285
    371 24 Karlskrona
    Mats Nilsson
    Skepparegatan 7 Lgh 1103
    371 30 Karlskrona
    2020-07-02
    AVRÄKNINGSNOTA
    1 (1)
    Svenska Handelsbanken AB (pub) Postadress: Gatuadress: Telefon: Bankgiro:
    Styrelsens säte: Stockholm Box 285 Skeppsbrokajen 8 +46 (0)455 36 74 00 861-3010
    Organisationsnr: 502007-7862 371 24 Karlskrona Telefax:
    Clearing nr: 6741 karlskrona@handelsbanken.se ()
    www.handelsbanken.se/karlskrona
    Handelsbanken Fonder AB
    Handelsbanken USA Ind Cri A1 SEK
    ISIN: SE0004139780
    Ingår i Investeringssparkonto 2 020 387 301
    Fondkontonr : 864 848 765
    Kundnummer : 710108-2910
    Text Belopp Kurs Andelar Saldo andelar
    Ingående saldo 2020-07-02 6.8291
    Avslut Självbetjän 2020-07-02 -3 103.28 454.42 -6.8291 0.0000
    Utbetalt belopp -3 103.28
    Insatt på konto: 596 961 901
    Omkostnadsbelopp 3 000.00
    Kapitalvinst 103.28
    Fondorder mottagen 2020-07-01 kl. 19:52
    Marknadsvärde 2020-07-02 0.00 454.42 0.0000
""".trimIndent()

class AvrakningsnotaPdfParserTest {

    @Test
    fun `parsar isin, fondnamn och kop ur en riktig avrakningsnota`() {
        val transactions = AvrakningsnotaPdfParser.parse(REAL_AVRAKNINGSNOTA, sourceFileName = "nota.pdf")

        assertEquals(1, transactions.size)
        val tx = transactions.first()
        assertEquals("SE0003653302", tx.isin)
        assertEquals("Nordea Fonder AB", tx.fundCompanyName)
        assertEquals("Småbolagsfond Sverige", tx.fundName)
        assertEquals(TransactionType.KOP, tx.type)
        assertEquals(LocalDate.of(2020, 3, 13).toEpochDay(), tx.epochDay)
        assertEquals(16.9862, tx.shares, 1e-9)
        assertEquals(294.36, tx.pricePerShare, 1e-9)
        assertEquals(5000.0, tx.amount, 1e-9)
        assertEquals("nota.pdf", tx.sourceFileName)
    }

    @Test
    fun `ingaende saldo och marknadsvarde-rader tolkas inte som transaktioner`() {
        // REAL_AVRAKNINGSNOTA innehåller både en "Ingående saldo"-rad och en
        // "Marknadsvärde"-rad — bara den enda riktiga "In"-raden ska ge en transaktion.
        val transactions = AvrakningsnotaPdfParser.parse(REAL_AVRAKNINGSNOTA, sourceFileName = "nota.pdf")
        assertEquals(1, transactions.size)
    }

    @Test
    fun `tolkar en salj-rad (Ut-prefix) som SALJ`() {
        val text = """
            Handelsbanken Fonder AB
            Handelsbanken Sverige (A1 SEK)
            ISIN: SE0000582033
            Text Belopp Kurs Andelar Saldo andelar
            Ut Självbetjäning 2021-05-04 2 000.00 350.00 5.7143 10.0000
        """.trimIndent()

        val transactions = AvrakningsnotaPdfParser.parse(text, sourceFileName = "salj.pdf")

        assertEquals(1, transactions.size)
        assertEquals(TransactionType.SALJ, transactions.first().type)
        assertEquals(5.7143, transactions.first().shares, 1e-9)
    }

    @Test
    fun `flera transaktionsrader i samma fil ger flera transaktioner`() {
        val text = """
            Nordea Fonder AB
            Småbolagsfond Sverige
            ISIN: SE0003653302
            Text Belopp Kurs Andelar Saldo andelar
            In Självbetjäning 2020-03-13 5 000.00 294.36 16.9862 16.9862
            In Självbetjäning 2020-06-01 2 500.00 310.00 8.0645 25.0507
        """.trimIndent()

        val transactions = AvrakningsnotaPdfParser.parse(text, sourceFileName = "flera.pdf")

        assertEquals(2, transactions.size)
        assertEquals(LocalDate.of(2020, 3, 13).toEpochDay(), transactions[0].epochDay)
        assertEquals(LocalDate.of(2020, 6, 1).toEpochDay(), transactions[1].epochDay)
    }

    @Test
    fun `ingen ISIN i texten ger tom lista`() {
        val transactions = AvrakningsnotaPdfParser.parse("Detta är inte en avräkningsnota.", sourceFileName = "fel.pdf")
        assertTrue(transactions.isEmpty())
    }

    @Test
    fun `parsar en riktig fullstandig-forsaljning (Avslut-prefix, negativa tal)`() {
        val transactions = AvrakningsnotaPdfParser.parse(REAL_AVRAKNINGSNOTA_SALJ, sourceFileName = "salj.pdf")

        assertEquals(1, transactions.size)
        val tx = transactions.first()
        assertEquals("SE0004139780", tx.isin)
        assertEquals("Handelsbanken Fonder AB", tx.fundCompanyName)
        assertEquals("Handelsbanken USA Ind Cri A1 SEK", tx.fundName)
        assertEquals(TransactionType.SALJ, tx.type)
        assertEquals(LocalDate.of(2020, 7, 2).toEpochDay(), tx.epochDay)
        // Käll-noten visar negativt belopp/andelar — parsern sparar magnituden, type bär riktningen.
        assertEquals(6.8291, tx.shares, 1e-9)
        assertEquals(454.42, tx.pricePerShare, 1e-9)
        assertEquals(3103.28, tx.amount, 1e-9)
        assertEquals("salj.pdf", tx.sourceFileName)
    }

    @Test
    fun `omkostnadsbelopp, kapitalvinst och utbetalt belopp tolkas inte som transaktioner`() {
        // REAL_AVRAKNINGSNOTA_SALJ innehåller flera extra rader unika för en sälj-nota —
        // bara den enda "Avslut"-raden ska ge en transaktion.
        val transactions = AvrakningsnotaPdfParser.parse(REAL_AVRAKNINGSNOTA_SALJ, sourceFileName = "salj.pdf")
        assertEquals(1, transactions.size)
    }
}
