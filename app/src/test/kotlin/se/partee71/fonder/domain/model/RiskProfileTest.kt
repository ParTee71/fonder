package se.partee71.fonder.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [RiskProfile]s migrering från #68:s skalära `targetRiskLevel` till #71:s
 * `targetAllocation`-fördelning. `PreferencesRepository.riskProfile` sväljer avkodningsfel
 * tyst via `runCatching { … }.getOrNull()` — ändras formen utan att det gamla fältet fortsatt
 * kan avkodas försvinner en redan sparad profil spårlöst, en verklig dataförlustbugg (issue
 * #71). Regressionstestet nedan avkodar rå, orörd #68-JSON (utan `targetAllocation` alls) och
 * verifierar att den blir `{N: 1.0}` — aldrig null.
 */
class RiskProfileTest {

    @Test
    fun `gammal SET-3-JSON utan targetAllocation avkodas till en fordelning, aldrig null`() {
        val legacyJson = """{"targetRiskLevel":4}"""

        val profile = Json.decodeFromString(RiskProfile.serializer(), legacyJson)

        assertEquals(mapOf(4 to 1.0), profile.effectiveAllocation)
    }

    @Test
    fun `gammal SET-3-JSON med svar avkodas och bevarar bade svar och migrerad fordelning`() {
        val legacyJson = """{"targetRiskLevel":2,"answers":{"horizon":"OVER_15_AR","reaction":"KOPER_MER","goal":"MAXIMAL_TILLVAXT"}}"""

        val profile = Json.decodeFromString(RiskProfile.serializer(), legacyJson)

        assertEquals(mapOf(2 to 1.0), profile.effectiveAllocation)
        assertEquals(TimeHorizon.OVER_15_AR, profile.answers?.horizon)
    }

    @Test
    fun `en satt targetAllocation vinner alltid over legacy-faltet`() {
        val profile = RiskProfile(targetAllocation = mapOf(3 to 0.5, 4 to 0.5), targetRiskLevel = 6)

        assertEquals(mapOf(3 to 0.5, 4 to 0.5), profile.effectiveAllocation)
    }

    @Test
    fun `ingen profil satt alls ger en tom effektiv fordelning`() {
        val profile = RiskProfile()

        assertEquals(emptyMap<Int, Double>(), profile.effectiveAllocation)
        assertNull(profile.weightedTargetLevel)
    }

    @Test
    fun `weightedTargetLevel racknar vardeviktat snitt av fordelningen`() {
        val profile = RiskProfile(targetAllocation = mapOf(3 to 0.25, 4 to 0.5, 5 to 0.25))

        // (3*0,25 + 4*0,5 + 5*0,25) = 0,75 + 2,0 + 1,25 = 4,0.
        assertEquals(4.0, profile.weightedTargetLevel ?: -1.0, 1e-9)
    }

    @Test
    fun `weightedTargetLevel for en migrerad legacy-niva ar exakt nivan`() {
        val profile = RiskProfile(targetRiskLevel = 5)

        assertEquals(5.0, profile.weightedTargetLevel ?: -1.0, 1e-9)
    }
}
