package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlinx.datetime.LocalDate as KotlinLocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.FundAnalysisCalc.SignalLevel
import se.partee71.fonder.testing.captureLightAndDark
import se.partee71.ui.components.DateField

/**
 * Skärmdumpar ljust + mörkt av varje delad komponent i `ui/components` (UI-12, skill
 * testing-strategy) med påhittad men realistisk svensk data. Beteendet testas i respektive
 * komponenttest; här är målet att utseendet i M3 Expressive-temat syns i skärmdumpsdiffen.
 * I en vanlig `testDebugUnitTest` renderas komponenterna utan att bilder skrivs.
 */
@RunWith(RobolectricTestRunner::class)
class ComponentScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `CardTitleRow med och utan indikator`() = composeRule.captureLightAndDark("CardTitleRow_working", animated = true) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CardTitleRow(title = "Kurser", working = true, style = MaterialTheme.typography.titleMedium)
            CardTitleRow(title = "Fondavgifter per år")
            ValueAsOfRow(navEpochDay = LocalDate.of(2026, 10, 10).toEpochDay())
        }
    }

    @Test
    fun `ValueAsOfRow`() = composeRule.captureLightAndDark("ValueAsOfRow_default") {
        ValueAsOfRow(navEpochDay = LocalDate.of(2026, 10, 10).toEpochDay())
    }

    @Test
    fun `WorkerStatusIcon och WorkingIndicator`() = composeRule.captureLightAndDark("WorkerStatusIcon_running", animated = true) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            WorkerStatusIcon(isRunning = true)
            WorkingIndicator(working = true, contentDescription = "Uppdaterar Kurser")
        }
    }

    @Test
    fun `ChoiceChipRow som knappgrupp`() = composeRule.captureLightAndDark("ChoiceChipRow_theme") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceChipRow(
                options = listOf("Ljust", "Mörkt", "Auto"),
                selected = "Auto",
                optionLabel = { it },
                onSelect = {}
            )
            ChoiceChipRow(
                options = listOf("Under 1 år", "1–3 år", "3–10 år", "Mer än 10 år", "Vet inte"),
                selected = "3–10 år",
                optionLabel = { it },
                onSelect = {}
            )
        }
    }

    @Test
    fun `PeriodRow vinst förlust och otillräcklig data`() = composeRule.captureLightAndDark("PeriodRow_variants") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PeriodRow(label = "I år", amount = 29_870.0, fraction = 0.078)
            PeriodRow(label = "1 år", amount = -4_520.0, fraction = -0.011)
            PeriodRow(label = "3 år", amount = null, fraction = null)
            PeriodRow(label = "Sedan köp", amount = 38_214.0, fraction = 0.102, stackValue = true)
            PeriodRow(label = "Volatilitet", amount = null, fraction = null, valueText = "14,8 %")
            PeriodRow(label = "Varav orealiserat", amount = 13_248.0, fraction = 0.12, muted = true)
            PeriodRow(label = "Årsavkastning (pengaviktad)", amount = null, fraction = null, insufficientText = "Visas efter ett år")
        }
    }

    @Test
    fun `RiskBadge och ProfitTakeBadge`() = composeRule.captureLightAndDark("Badges_default") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RiskBadge(level = 4)
                RiskBadge(level = 4, toLevel = 6)
                RiskBadge(level = null)
            }
            ProfitTakeBadge(gainFraction = 0.42)
        }
    }

    @Test
    fun `ExposureBar mål och utfall`() = composeRule.captureLightAndDark("ExposureBar_default") {
        Column {
            ExposureBar(label = "Aktiefonder", fraction = 0.75)
            ExposureBar(label = "Räntefonder", fraction = 0.2, color = MaterialTheme.colorScheme.secondary)
            ExposureBar(label = "Okänd fondtyp", fraction = 0.05, color = MaterialTheme.colorScheme.outline)
        }
    }

    @Test
    fun `AnalysisStatus banner och vägledning`() = composeRule.captureLightAndDark("AnalysisStatus_gul") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AnalysisStatusBanner(analysis = sampleAnalysis)
            AnalysisGuidanceCard(analysis = sampleAnalysis)
            AnalysisStatusBanner(analysis = null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(SignalLevel.GRON)
                StatusDot(SignalLevel.GUL)
                StatusDot(SignalLevel.ROD)
            }
        }
    }

    @Test
    fun `ExpandableSection och ExpandableInfoRow`() = composeRule.captureLightAndDark("Expandable_default") {
        Column {
            ExpandableSection(title = "Avgifter") { Text("Visas när sektionen fälls ut.") }
            ExpandableInfoRow(
                explanation = "Risknivån bygger på hur mycket kursen svängt de senaste fem åren, på en skala 1–7.",
                initiallyExpanded = true
            ) {
                Text("Varför Risk 4?")
            }
        }
    }

    @Test
    fun `DateField SelectField och FollowedToggleRow`() = composeRule.captureLightAndDark("FormFields_default") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DateField(label = "Datum", date = KotlinLocalDate(2026, 8, 12), onDateChange = {})
            SelectField(
                label = "Typ",
                options = listOf("Köp", "Sälj"),
                selected = "Köp",
                optionLabel = { it },
                onSelect = {}
            )
            FollowedToggleRow(followed = true, onFollowedChange = {})
        }
    }

    @Test
    fun `EmptyState med ikon`() = composeRule.captureLightAndDark("EmptyState_icon") {
        EmptyState(
            title = "Inga transaktioner än",
            body = "Lägg till ett köp eller importera en avräkningsnota.",
            icon = Icons.AutoMirrored.Outlined.List,
            modifier = Modifier.height(320.dp)
        )
    }

    @Test
    fun `ImportCompleteDialog`() = composeRule.captureLightAndDark("ImportCompleteDialog_default", screen = true) {
        ImportCompleteDialog(importedCount = 12, onDismiss = {})
    }

    @Test
    fun `PullToRefreshContainer under uppdatering`() = composeRule.captureLightAndDark("PullToRefreshContainer_refreshing", animated = true) {
        PullToRefreshContainer(refreshing = true, onRefresh = {}, modifier = Modifier.height(160.dp)) {
            Column {
                PeriodRow(label = "Idag", amount = 1_284.0, fraction = 0.0031)
            }
        }
    }

    private companion object {
        val sampleAnalysis = FundAnalysisCalc.Analysis(
            keyFigures = FundAnalysisCalc.KeyFigures(
                periodReturns = emptyList(),
                cagr = 0.064,
                currentNav = 312.45,
                gavPerShare = 298.10,
                gavFraction = 0.048,
                portfolioShareFraction = 0.22,
                annualizedVolatility = 0.148,
                sharpeRatio = 0.7
            ),
            distanceFromHigh = FundAnalysisCalc.DistanceFromHighSignal(SignalLevel.GUL, -0.142),
            trend = FundAnalysisCalc.TrendSignal(SignalLevel.GUL),
            momentum = null,
            status = SignalLevel.GUL,
            profitTake = null
        )
    }
}
