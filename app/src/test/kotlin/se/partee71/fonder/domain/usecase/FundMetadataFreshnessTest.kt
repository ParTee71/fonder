package se.partee71.fonder.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FundMetadataFreshnessTest {

    private val today = LocalDate.of(2026, 7, 30)

    @Test
    fun `nyare an TTL ar inte inaktuell`() {
        val resolvedAt = today.minusDays(10).toEpochDay()

        assertFalse(FundMetadataFreshness.isStale(resolvedAt, today))
    }

    @Test
    fun `exakt pa TTL-gransen ar inte inaktuell`() {
        val resolvedAt = today.minusDays(FundMetadataFreshness.AVAILABILITY_TTL_DAYS).toEpochDay()

        assertFalse(FundMetadataFreshness.isStale(resolvedAt, today))
    }

    @Test
    fun `aldre an TTL ar inaktuell`() {
        val resolvedAt = today.minusDays(FundMetadataFreshness.AVAILABILITY_TTL_DAYS + 1).toEpochDay()

        assertTrue(FundMetadataFreshness.isStale(resolvedAt, today))
    }

    @Test
    fun `FEE_TTL_DAYS anvands nar ttlDays anges explicit, exakt pa gransen ar inte inaktuell`() {
        val resolvedAt = today.minusDays(FundMetadataFreshness.FEE_TTL_DAYS).toEpochDay()

        assertFalse(FundMetadataFreshness.isStale(resolvedAt, today, FundMetadataFreshness.FEE_TTL_DAYS))
    }

    @Test
    fun `FEE_TTL_DAYS pluss en dag ar inaktuell`() {
        val resolvedAt = today.minusDays(FundMetadataFreshness.FEE_TTL_DAYS + 1).toEpochDay()

        assertTrue(FundMetadataFreshness.isStale(resolvedAt, today, FundMetadataFreshness.FEE_TTL_DAYS))
    }

    @Test
    fun `COMPARISON_TTL_DAYS exakt pa gransen ar inte inaktuell`() {
        val resolvedAt = today.minusDays(FundMetadataFreshness.COMPARISON_TTL_DAYS).toEpochDay()

        assertFalse(FundMetadataFreshness.isStale(resolvedAt, today, FundMetadataFreshness.COMPARISON_TTL_DAYS))
    }

    @Test
    fun `COMPARISON_TTL_DAYS pluss en dag ar inaktuell`() {
        val resolvedAt = today.minusDays(FundMetadataFreshness.COMPARISON_TTL_DAYS + 1).toEpochDay()

        assertTrue(FundMetadataFreshness.isStale(resolvedAt, today, FundMetadataFreshness.COMPARISON_TTL_DAYS))
    }
}
