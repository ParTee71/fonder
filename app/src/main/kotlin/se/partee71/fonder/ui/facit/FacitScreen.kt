package se.partee71.fonder.ui.facit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import se.partee71.fonder.R
import se.partee71.fonder.domain.usecase.MoneyFormat
import se.partee71.fonder.domain.usecase.SwitchOutcomeCalc
import se.partee71.fonder.ui.components.EmptyState
import se.partee71.fonder.ui.components.PeriodRow

private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/** Gör listan adresserbar för `performScrollToIndex` i tester (UI-5, samma skäl som Portfölj). */
const val FACIT_LIST_TEST_TAG = "facit_list"

/**
 * Facit för bytesplanen (SET-5, issue #80) — egen undersida från Inställningar, samma mönster
 * som Riskprofil (SET-3).
 */
@Composable
fun FacitScreen(
    modifier: Modifier = Modifier,
    viewModel: FacitViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    FacitContent(state = state, onFollowedChange = viewModel::setFollowed, modifier = modifier)
}

/** Tillståndsdriven, testbar del av [FacitScreen] — inget ViewModel/Hilt-beroende (issue #21-mönstret). */
@Composable
fun FacitContent(
    state: FacitUiState,
    onFollowedChange: (Long, Boolean) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    if (state.isEmpty) {
        EmptyState(
            title = stringResource(R.string.facit_empty_title),
            body = stringResource(R.string.facit_empty_body),
            modifier = modifier,
        )
    } else {
        // Summeringskortet ligger som `item {}` i samma lista som raderna, inte i en fast
        // Column ovanför — annars äter det permanent skärmhöjd och kan klippa bort förslag på
        // en liten skärm, exakt buggklassen UI-5 beskriver.
        LazyColumn(modifier = modifier.fillMaxSize().testTag(FACIT_LIST_TEST_TAG)) {
            item { SummaryCard(state = state) }
            items(state.rows, key = { it.recordId }) { row ->
                FacitRadCard(row = row, onFollowedChange = onFollowedChange)
            }
            item {
                Text(
                    stringResource(R.string.facit_disclaimer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                )
            }
        }
    }
}

/**
 * De två måtten och snittet per plats i planen. **Alla förslag** och **enbart genomförda**
 * står som skilda rader och slås aldrig ihop: ett oföljt förslag är ett hypotetiskt utfall,
 * ett följt ett verkligt, och en gemensam siffra hade mätt ingetdera (SET-5).
 */
@Composable
private fun SummaryCard(state: FacitUiState) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.facit_summary_title), style = MaterialTheme.typography.labelMedium)
            SummaryRow(
                label = stringResource(R.string.facit_summary_all_label),
                summary = state.allSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
            SummaryRow(
                label = stringResource(R.string.facit_summary_followed_label),
                summary = state.followedSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                stringResource(
                    R.string.format_facit_evaluated_count,
                    state.allSummary.evaluatedCount,
                    state.allSummary.totalCount,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (state.byPlanIndex.isNotEmpty()) {
                Text(
                    stringResource(R.string.facit_by_plan_index_title),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
                state.byPlanIndex.forEach { entry ->
                    SummaryRow(
                        label = stringResource(R.string.format_facit_plan_index_label, entry.planIndex + 1),
                        summary = entry.summary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    stringResource(R.string.facit_by_plan_index_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * En summeringsrad via den delade [PeriodRow] (regel 4) — procenten är huvudmåttet och
 * kronorna dess konsekvens, därför [PeriodRow]s `stackValue`. Utan utvärderade förslag
 * saknas båda talen, och raden faller tillbaka på komponentens egen "otillräcklig data".
 */
@Composable
private fun SummaryRow(label: String, summary: SwitchOutcomeCalc.Summary, modifier: Modifier = Modifier) {
    PeriodRow(
        label = label,
        amount = summary.totalExcessKr,
        fraction = summary.averageExcessReturn,
        stackValue = true,
        modifier = modifier,
    )
}

/**
 * Ett inspelat förslag. Hopfällbart av samma skäl som sälj-korten i Sålda fonder (SLD-4) —
 * stängt visas datum, fonderna och utfallet, expanderat detaljerna. Byggs lokalt i stället för
 * att återanvända `ExpandableInfoRow`, av exakt samma skäl som `SoldFundRow` dokumenterar: den
 * komponenten fäller ut en förklarande klartext, här är det radens egna detaljer som ska
 * döljas. Interaktionsmönstret (pil, ≥48 dp träffyta, `rememberSaveable`) återanvänds ändå.
 *
 * "Genomförd" ligger här *och* på Hem (HEM-8): planen på Hem försvinner efter
 * `SwitchPlanCalc.PLAN_TTL_DAYS`, så utan kryssrutan här gick ett äldre förslag aldrig att
 * markera i efterhand — och då hade det följda måttet aldrig kunnat bli komplett.
 */
@Composable
private fun FacitRadCard(row: FacitRad, onFollowedChange: (Long, Boolean) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable { expanded = !expanded }
                .padding(16.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(
                            R.string.format_facit_row_funds,
                            row.sellFundName,
                            row.buyFundName,
                        ),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        LocalDate.ofEpochDay(row.suggestedAtEpochDay).format(dateFormatter),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.facit_collapse else R.string.facit_expand,
                    ),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Utfallet via den delade PeriodRow (regel 4): procent + kronor, färgat efter
            // procenten. Är raden inte utvärderad ännu är båda talen null och komponenten
            // markerar den som otillräcklig data — aldrig som 0, som lästs som "gav ingenting".
            PeriodRow(
                label = stringResource(R.string.facit_row_excess_label),
                amount = row.outcome.excessKr,
                fraction = row.outcome.excessReturn,
                stackValue = true,
                modifier = Modifier.padding(top = 8.dp),
            )

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        stringResource(R.string.format_facit_plan_index_detail, row.planIndex + 1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    row.switchValueKr?.let { valueKr ->
                        Text(
                            stringResource(R.string.format_facit_amount, MoneyFormat.kr(valueKr)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (row.outcome.isEvaluated) {
                        Text(
                            stringResource(
                                R.string.format_facit_legs,
                                MoneyFormat.percentSigned(row.outcome.sellReturn!!),
                                MoneyFormat.percentSigned(row.outcome.buyReturn!!),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            stringResource(R.string.facit_not_evaluated_explain),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // `toggleable` på hela raden i stället för bara på rutan: etiketten blir
                    // klickbar, träffytan når 48 dp och skärmläsaren får **en** nod med rollen
                    // kryssruta i stället för en ruta plus en löstext bredvid.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(top = 4.dp)
                            .toggleable(
                                value = row.followed,
                                role = Role.Checkbox,
                                onValueChange = { checked -> onFollowedChange(row.recordId, checked) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = row.followed, onCheckedChange = null)
                        Text(stringResource(R.string.facit_followed_label), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
