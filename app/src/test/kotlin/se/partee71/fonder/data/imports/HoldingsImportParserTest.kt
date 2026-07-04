package se.partee71.fonder.data.imports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class HoldingsImportParserTest {

    // Verklig xl/worksheets/sheet1.xml-fragment ur en Handelsbanken "Innehav Fonder"-export
    // (issue #8) — strängar inline (t="inlineStr"), inga delade strängar.
    private val sampleSheetXml = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
            <dimension ref="A1"/>
            <sheetViews>
                <sheetView tabSelected="1" workbookViewId="0"/>
            </sheetViews>
            <sheetFormatPr defaultRowHeight="15"/>
            <sheetData>
            <row r="1"><c r="A1" t="inlineStr"><is><t>Handelsbanken</t></is></c></row><row r="2"><c r="A2" t="inlineStr"><is><t>2026-07-04, 07:54</t></is></c></row><row r="3"><c r="A3" t="inlineStr"><is><t></t></is></c></row><row r="4"><c r="A4" t="inlineStr"><is><t>Innehav Fonder - 2 020 387 301</t></is></c></row><row r="5"><c r="A5" t="inlineStr"><is><t>ISIN</t></is></c><c r="B5" t="inlineStr"><is><t>Kortnamn</t></is></c><c r="C5" t="inlineStr"><is><t>Värdepapper</t></is></c><c r="D5" t="inlineStr"><is><t>Antal</t></is></c><c r="E5" t="inlineStr"><is><t>Senast</t></is></c><c r="F5" t="inlineStr"><is><t>Valuta</t></is></c><c r="G5" t="inlineStr"><is><t>Kursdatum</t></is></c><c r="H5" t="inlineStr"><is><t>Marknadsvärde (SEK)</t></is></c><c r="I5" t="inlineStr"><is><t>Anskaffningsvärde (SEK)</t></is></c><c r="J5" t="inlineStr"><is><t>Värdeutveckling (SEK)</t></is></c></row><row r="6"><c r="A6" t="inlineStr"><is><t>SE0001185000</t></is></c><c r="B6" t="inlineStr"><is><t>AMF Fonder</t></is></c><c r="C6" t="inlineStr"><is><t>AMF Fonder Aktiefond Småbolag</t></is></c><c r="D6" t="inlineStr"><is><t>5,2594</t></is></c><c r="E6"><v>1212.24</v></c><c r="F6" t="inlineStr"><is><t>SEK</t></is></c><c r="G6" t="inlineStr"><is><t>2026-07-02</t></is></c><c r="H6"><v>6375.66</v></c><c r="I6"><v>5000</v></c><c r="J6"><v>1375.66</v></c></row><row r="7"><c r="A7" t="inlineStr"><is><t>LU1989766289</t></is></c><c r="B7" t="inlineStr"><is><t>Amundi Luxembourg</t></is></c><c r="C7" t="inlineStr"><is><t>Amundi Luxembourg CPR Invest - Global Gold Mines</t></is></c><c r="D7" t="inlineStr"><is><t>7,6973</t></is></c><c r="E7"><v>1961.08</v></c><c r="F7" t="inlineStr"><is><t>SEK</t></is></c><c r="G7" t="inlineStr"><is><t>2026-07-02</t></is></c><c r="H7"><v>15095.02</v></c><c r="I7"><v>11674.91</v></c><c r="J7"><v>3420.11</v></c></row><row r="8"><c r="A8" t="inlineStr"><is><t>LU0496367417</t></is></c><c r="B8" t="inlineStr"><is><t>Franklin Templeton</t></is></c><c r="C8" t="inlineStr"><is><t>Franklin Templeton Franklin Gold and Precious Metals Fund</t></is></c><c r="D8" t="inlineStr"><is><t>116,2080</t></is></c><c r="E8"><v>175.12</v></c><c r="F8" t="inlineStr"><is><t>SEK</t></is></c><c r="G8" t="inlineStr"><is><t>2026-07-02</t></is></c><c r="H8"><v>20350.34</v></c><c r="I8"><v>15000</v></c><c r="J8"><v>5350.34</v></c></row><row r="9"><c r="A9" t="inlineStr"><is><t>LU0496367417</t></is></c><c r="B9" t="inlineStr"><is><t>Franklin Templeton</t></is></c><c r="C9" t="inlineStr"><is><t>Franklin Templeton Franklin Gold and Precious Metals Fund</t></is></c><c r="D9" t="inlineStr"><is><t>98,7690</t></is></c><c r="E9"><v>175.12</v></c><c r="F9" t="inlineStr"><is><t>SEK</t></is></c><c r="G9" t="inlineStr"><is><t>2026-07-02</t></is></c><c r="H9"><v>17296.43</v></c><c r="I9"><v>16474.4</v></c><c r="J9"><v>822.03</v></c></row><row r="10"><c r="A10" t="inlineStr"><is><t>SE0000582033</t></is></c><c r="B10" t="inlineStr"><is><t>Handelsbanken Fonder AB</t></is></c><c r="C10" t="inlineStr"><is><t>Handelsbanken Sverige (A1 SEK)</t></is></c><c r="D10" t="inlineStr"><is><t>1,9378</t></is></c><c r="E10"><v>3949.49</v></c><c r="F10" t="inlineStr"><is><t>SEK</t></is></c><c r="G10" t="inlineStr"><is><t>2026-07-03</t></is></c><c r="H10"><v>7653.32</v></c><c r="I10"><v>5187</v></c><c r="J10"><v>2466.32</v></c></row><row r="11"><c r="A11" t="inlineStr"><is><t>SE0003653302</t></is></c><c r="B11" t="inlineStr"><is><t>Nordea Fonder AB</t></is></c><c r="C11" t="inlineStr"><is><t>Nordea Fonder AB Småbolagsfond Sverige</t></is></c><c r="D11" t="inlineStr"><is><t>24,6020</t></is></c><c r="E11"><v>640.93</v></c><c r="F11" t="inlineStr"><is><t>SEK</t></is></c><c r="G11" t="inlineStr"><is><t>2026-07-02</t></is></c><c r="H11"><v>15768.16</v></c><c r="I11"><v>10000</v></c><c r="J11"><v>5768.16</v></c></row><row r="12"><c r="A12" t="inlineStr"><is><t>FI0008813365</t></is></c><c r="B12" t="inlineStr"><is><t>Nordea Rahastoyhtiö Suomi Oy</t></is></c><c r="C12" t="inlineStr"><is><t>Nordea Rahastoyhtiö Suomi Oy Småbolagsfond Norden</t></is></c><c r="D12" t="inlineStr"><is><t>130,5446</t></is></c><c r="E12"><v>329.58</v></c><c r="F12" t="inlineStr"><is><t>SEK</t></is></c><c r="G12" t="inlineStr"><is><t>2026-07-02</t></is></c><c r="H12"><v>43024.89</v></c><c r="I12"><v>22000</v></c><c r="J12"><v>21024.89</v></c></row>
            </sheetData>
            <pageMargins left="0.7" right="0.7" top="0.75" bottom="0.75" header="0.3" footer="0.3"/>
        </worksheet>
    """.trimIndent()

    @Test
    fun `parsar sju rader ur den verkliga exportfilen`() {
        val rows = HoldingsImportParser.parseSheetXml(sampleSheetXml, sharedStrings = emptyList())
        assertEquals(7, rows.size)
    }

    @Test
    fun `forsta raden tolkas ratt inklusive svenskt decimalkomma`() {
        val rows = HoldingsImportParser.parseSheetXml(sampleSheetXml, sharedStrings = emptyList())
        val first = rows.first()

        assertEquals("SE0001185000", first.isin)
        assertEquals("AMF Fonder", first.fundCompanyName)
        assertEquals("AMF Fonder Aktiefond Småbolag", first.fundName)
        assertEquals(5.2594, first.shares, 1e-9)
        assertEquals(1212.24, first.latestNav, 1e-9)
        assertEquals("SEK", first.currency)
        assertEquals(LocalDate.of(2026, 7, 2).toEpochDay(), first.quoteEpochDay)
        assertEquals(6375.66, first.marketValue, 1e-9)
        assertEquals(5000.0, first.acquisitionValue, 1e-9)
    }

    @Test
    fun `samma ISIN forekommer som tva separata rader`() {
        val rows = HoldingsImportParser.parseSheetXml(sampleSheetXml, sharedStrings = emptyList())
        val franklinRows = rows.filter { it.isin == "LU0496367417" }

        assertEquals(2, franklinRows.size)
        assertEquals(116.208, franklinRows[0].shares, 1e-9)
        assertEquals(98.769, franklinRows[1].shares, 1e-9)
    }

    @Test
    fun `averageCostPerShare beraknas fran anskaffningsvarde och antal`() {
        val rows = HoldingsImportParser.parseSheetXml(sampleSheetXml, sharedStrings = emptyList())
        val first = rows.first()

        assertEquals(5000.0 / 5.2594, first.averageCostPerShare, 1e-6)
    }

    @Test
    fun `tom sheetXml utan ISIN-headerrad ger tom lista`() {
        val xml = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                <row r="1"><c r="A1" t="inlineStr"><is><t>Ingen rubrikrad har</t></is></c></row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        assertEquals(0, HoldingsImportParser.parseSheetXml(xml, emptyList()).size)
    }

    @Test
    fun `delade strangar (t=s) loses upp via sharedStrings-listan`() {
        val xml = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                <row r="5"><c r="A5" t="s"><v>0</v></c></row>
                <row r="6"><c r="A6" t="s"><v>1</v></c><c r="B6" t="s"><v>2</v></c><c r="C6" t="s"><v>3</v></c><c r="D6" t="s"><v>4</v></c><c r="E6"><v>100.0</v></c><c r="F6" t="s"><v>5</v></c><c r="G6" t="s"><v>6</v></c><c r="H6"><v>200.0</v></c><c r="I6"><v>150.0</v></c></row>
                </sheetData>
            </worksheet>
        """.trimIndent()
        val sharedStrings = listOf("ISIN", "SE1234567890", "Test Bolag", "Testfond", "1,0000", "SEK", "2026-01-01")

        val rows = HoldingsImportParser.parseSheetXml(xml, sharedStrings)

        assertEquals(1, rows.size)
        assertEquals("SE1234567890", rows.first().isin)
        assertEquals("Testfond", rows.first().fundName)
        assertEquals(1.0, rows.first().shares, 1e-9)
    }

    @Test
    fun `parse hanterar ozippad ra-XML direkt (verklig exportform)`() {
        // Handelsbankens export visade sig inte vara en riktig zip-baserad .xlsx, bara det
        // inre kalkylbladets råa XML — parse() ska tolka den direkt utan zip-uppackning.
        val rows = HoldingsImportParser.parse(sampleSheetXml.toByteArray(Charsets.UTF_8).inputStream())
        assertEquals(7, rows.size)
    }

    @Test
    fun `parse packar upp en zippad xlsx om zip-magibytena hittas`() {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(sampleSheetXml.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val rows = HoldingsImportParser.parse(bytes.toByteArray().inputStream())
        assertEquals(7, rows.size)
    }

    @Test
    fun `rad utan antal hoppas over`() {
        val xml = """
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                <row r="5"><c r="A5" t="inlineStr"><is><t>ISIN</t></is></c></row>
                <row r="6"><c r="A6" t="inlineStr"><is><t>SE0000000000</t></is></c><c r="I6"><v>100.0</v></c></row>
                </sheetData>
            </worksheet>
        """.trimIndent()

        assertNull(HoldingsImportParser.parseSheetXml(xml, emptyList()).firstOrNull())
    }
}
