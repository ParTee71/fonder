package se.partee71.fonder.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.partee71.fonder.domain.model.Fund
import se.partee71.fonder.domain.model.FundCompany
import se.partee71.fonder.domain.usecase.FundCompanyMatcher

class FundCompanyMatcherTest {

    private val handelsbanken = FundCompany(id = FundCompany.HANDELSBANKEN_ID, name = "Handelsbanken")

    @Test
    fun `Handelsbanken matchar via SHB-prefix aven for XACT som inte har namnet i fondnamnet`() {
        val xact = Fund(fundId = "SHB0000999", name = "XACT Sverige (UCITS ETF)")
        val handelsbankenFond = Fund(fundId = "SHB0000442", name = "Handelsbanken Amerika Småbolag Tema")
        val extern = Fund(fundId = "0P000083RV", name = "AstraZeneca Allemansfond")

        assertTrue(FundCompanyMatcher.matches(xact, handelsbanken))
        assertTrue(FundCompanyMatcher.matches(handelsbankenFond, handelsbanken))
        assertFalse(FundCompanyMatcher.matches(extern, handelsbanken))
    }

    @Test
    fun `ovriga bolag matchar via namnprefix efter att bolagsform stadats bort`() {
        val aberdeen = FundCompany(id = "1101", name = "Aberdeen Global Services S.A.")
        val fund = Fund(fundId = "0P0000AAAA", name = "Aberdeen Global – Asia Pacific Equity Fund")
        val other = Fund(fundId = "0P0000BBBB", name = "Alcur Select")

        assertTrue(FundCompanyMatcher.matches(fund, aberdeen))
        assertFalse(FundCompanyMatcher.matches(other, aberdeen))
    }

    @Test
    fun `coreBrandName stadar bort bolagsform och parentes`() {
        assertEquals("Aberdeen", FundCompanyMatcher.coreBrandName("Aberdeen Global Services S.A."))
        assertEquals("Alfred Berg", FundCompanyMatcher.coreBrandName("Alfred Berg Kapitalforvaltning AS"))
        assertEquals("AllianceBernstein", FundCompanyMatcher.coreBrandName("AllianceBernstein (Luxembourg) S.A."))
        assertEquals("Aktie-Ansvar", FundCompanyMatcher.coreBrandName("Aktie-Ansvar AB"))
    }
}
