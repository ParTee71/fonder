package se.partee71.fonder.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import se.partee71.fonder.domain.usecase.FundCompanyMatcher

/**
 * `matches`-testerna är borta tillsammans med funktionen (issue #37): kopplingen fond →
 * fondbolag gissas inte längre i appen, den hämtas från källans `company`-filter
 * (`FundPriceRepository.fetchFundsForCompany`, se `FundSearchViewModelTest`). Kvar är
 * [FundCompanyMatcher.coreBrandName], som `FundNameMatcher` fortfarande använder för att ge
 * rätt fondbolags kandidater ett försprång vid importmatchning (TP-13).
 */
class FundCompanyMatcherTest {

    @Test
    fun `coreBrandName stadar bort bolagsform och parentes`() {
        assertEquals("Aberdeen", FundCompanyMatcher.coreBrandName("Aberdeen Global Services S.A."))
        assertEquals("Alfred Berg", FundCompanyMatcher.coreBrandName("Alfred Berg Kapitalforvaltning AS"))
        assertEquals("AllianceBernstein", FundCompanyMatcher.coreBrandName("AllianceBernstein (Luxembourg) S.A."))
        assertEquals("Aktie-Ansvar", FundCompanyMatcher.coreBrandName("Aktie-Ansvar AB"))
    }

    @Test
    fun `coreBrandName lamnar ett namn utan bolagsform orort`() {
        assertEquals("Handelsbanken", FundCompanyMatcher.coreBrandName("Handelsbanken"))
        assertEquals("CPR", FundCompanyMatcher.coreBrandName("CPR Asset Management"))
    }
}
