package se.partee71.fonder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlin.math.cos
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate as KotlinLocalDate
import se.partee71.fonder.R
import se.partee71.fonder.data.common.AppErrors
import se.partee71.fonder.data.common.DataError
import se.partee71.fonder.domain.usecase.FundAnalysisCalc
import se.partee71.fonder.domain.usecase.FundAnalysisCalc.SignalLevel
import se.partee71.fonder.domain.usecase.PortfolioTotalCalc
import se.partee71.fonder.ui.diagram.ChartSeries
import se.partee71.fonder.ui.diagram.FundLineChart
import se.partee71.fonder.ui.theme.Copper
import se.partee71.fonder.ui.theme.Error
import se.partee71.fonder.ui.theme.Filled
import se.partee71.fonder.ui.theme.Gain
import se.partee71.fonder.ui.theme.Neutral
import se.partee71.fonder.ui.theme.NeutralPrimary
import se.partee71.fonder.ui.theme.Tertiary
import se.partee71.fonder.ui.theme.flushCardStyle
import se.partee71.ui.components.AppButton
import se.partee71.ui.components.AppCard
import se.partee71.ui.components.AppDivider
import se.partee71.ui.components.AppIconButton
import se.partee71.ui.components.AppTextField
import se.partee71.ui.components.AppTopBar as LibraryTopBar
import se.partee71.ui.components.ButtonVariant
import se.partee71.ui.components.CardSegment
import se.partee71.ui.components.ConfirmDialog
import se.partee71.ui.components.DateField
import se.partee71.ui.components.EmptyAction
import se.partee71.ui.components.EmptyState as LibraryEmptyState
import se.partee71.ui.components.IconButtonVariant
import se.partee71.ui.components.InfoPill
import se.partee71.ui.components.ItemRow
import se.partee71.ui.components.LoadErrorState
import se.partee71.ui.components.LocalTopBarStatus
import se.partee71.ui.components.R as UiR
import se.partee71.ui.components.SectionHeader
import se.partee71.ui.components.SwitchRow
import se.partee71.ui.components.TopBarSize
import se.partee71.ui.theme.AppTheme
import se.partee71.ui.theme.Spacing
import se.partee71.ui.theme.Tone

/**
 * Alla Fonders delade komponenter (`ui/components`, `ui/diagram`) och de android-ui-delar appen
 * använder, i sina lägen och med påhittad data – aldrig användarens innehav (skill
 * shared-ui-components). Nås från Inställningar → "Komponenter", bara i debug-bygget.
 *
 * Skärmen är en ramskärm (`FrameKey`): egen `Scaffold` med bibliotekets topprad, som får chromets
 * bakgrundsindikator via `LocalTopBarStatus`. Diagrammet ligger i en hopfälld sektion, så att det
 * bara ritas när man fäller ut det (Vico bygger modellen på en annan tråd).
 */
@Composable
fun ComponentGallery(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            LibraryTopBar(
                stringResource(R.string.component_gallery_title),
                modifier = Modifier.testTag(COMPONENT_GALLERY_TOP_BAR_TAG),
                size = TopBarSize.Small,
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.l, vertical = Spacing.s),
            verticalArrangement = Arrangement.spacedBy(Spacing.xl)
        ) {
            GallerySection(R.string.gallery_section_buttons) { Buttons() }
            GallerySection(R.string.gallery_section_cards) { CardsAndRows() }
            GallerySection(R.string.gallery_section_fields) { Fields() }
            GallerySection(R.string.gallery_section_values) { Values() }
            GallerySection(R.string.gallery_section_status) { Status() }
            GallerySection(R.string.gallery_section_navigation) { Navigation() }
            GallerySection(R.string.gallery_section_dialogs) { Dialogs() }
            GallerySection(R.string.gallery_section_library) { Library() }
            ExpandableSection(title = stringResource(R.string.gallery_section_chart), titleStyle = MaterialTheme.typography.headlineSmall) {
                Chart()
            }
        }
    }
}

@Composable
private fun GallerySection(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() }
        )
        content()
    }
}

/** Komponentens namn över exemplet – ett identifierarnamn, ingen användartext. */
@Composable
private fun Label(name: String) {
    Text(
        name,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = Spacing.s)
    )
}

@Composable
private fun ColumnScope.Buttons() {
    Label("AppButton (android-ui, Fonders knappstil)")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        AppButton("Spara", {})
        AppButton("Välj fond", {}, variant = ButtonVariant.Secondary)
        AppButton("Följ", {}, variant = ButtonVariant.Outlined)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        AppButton("Avstå", {}, variant = ButtonVariant.Text)
        AppButton("Töm databasen", {}, variant = ButtonVariant.Destructive)
        AppButton("Spara", {}, enabled = false)
    }
    Label("AppIconButton (android-ui)")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        AppIconButton(R.drawable.ic_settings, "Inställningar", {}, variant = IconButtonVariant.Tonal, tone = Tone.NeutralPrimary)
        AppIconButton(UiR.drawable.ic_close, "Stäng", {})
        AppIconButton(UiR.drawable.ic_arrow_back, "Tillbaka", {}, enabled = false)
    }
}

@Composable
private fun ColumnScope.CardsAndRows() {
    Label("SectionHeader (android-ui) · CardTitleRow")
    SectionHeader(title = "Byten", count = "2 väntar på dig", working = true, workingDescription = "Uppdaterar Byten")
    Label("AppCard (android-ui, Fonders kortstil)")
    AppCard(modifier = Modifier.fillMaxWidth()) {
        CardTitleRow(title = "Fondavgifter per år", working = true, style = AppTheme.typography.itemTitle)
        Text("Kontur och 16 dp luft.", style = MaterialTheme.typography.bodySmall)
    }
    AppCard(modifier = Modifier.fillMaxWidth(), tone = Tone.Filled, onClick = {}) {
        Text("Klickbart och tonat (Tone.Filled).", style = MaterialTheme.typography.bodyMedium)
    }
    AppCard(modifier = Modifier.fillMaxWidth(), tone = Tone.Error, contentColor = MaterialTheme.colorScheme.onErrorContainer) {
        Text("Farozonen (Tone.Error).")
    }
    Label("TotalCard")
    TotalCard(
        totalValue = 412_380.5,
        totalInvested = 360_000.0,
        gainLoss = 52_380.5,
        gainLossFraction = 0.1455,
        total = PortfolioTotalCalc.Result(netDepositsKr = 365_000.0, totalGainKr = 47_380.5, totalGainFraction = 0.1298, moneyWeightedAnnualReturn = 0.071, partial = false, ageDays = 812),
        navEpochDay = 20_742,
        working = true
    )
    Label("CardSegment · ItemRow · AppDivider (android-ui)")
    val rows = listOf(
        Triple("Köp", "Global Indexfond A", "+12 000 kr"),
        Triple("Sälj", "Tillväxt Norden", "−4 300 kr"),
        Triple("Köp", "Ränta Kort", "+2 500 kr")
    )
    Column {
        rows.forEachIndexed { index, (type, fund, amount) ->
            CardSegment(top = index == 0, bottom = index == rows.lastIndex, style = flushCardStyle) {
                ItemRow(
                    title = fund,
                    subtitle = "14 okt 2026",
                    overline = { Text(type, style = AppTheme.typography.caption) },
                    trailing = { Text(amount, style = MaterialTheme.typography.bodyMedium) }
                )
            }
        }
    }
    AppCard(modifier = Modifier.fillMaxWidth(), style = flushCardStyle) {
        ItemRow(title = "Mot riskprofil 4,00", subtitle = "Balanserad tillväxt · ISK/KF")
        AppDivider()
        ItemRow(title = "1 genomfört förslag", trailing = { Text("+1 240 kr", style = MaterialTheme.typography.bodyLarge) }, navigates = true, onClick = {})
    }
    Label("InfoPill (android-ui, 8 dp) · RiskBadge · ProfitTakeBadge · CountBadge · StatusDot")
    galleryTones.chunked(CHIPS_PER_ROW).forEach { tones ->
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            tones.forEach { (name, tone) -> InfoPill(text = name, tone = tone, shape = AppTheme.shapes.smallTile) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalAlignment = Alignment.CenterVertically) {
        RiskBadge(level = 3)
        RiskBadge(level = 5, toLevel = 4)
        ProfitTakeBadge(gainFraction = 0.35)
        CountBadge(count = 2)
        CountBadge(count = 5, size = CountBadgeSize.Large)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SignalLevel.entries.forEach { StatusDot(it) }
    }
    Label("ReminderRow")
    ReminderRow(
        title = "Säkerhetskopiera",
        body = "Senaste kopian är 12 dagar gammal.",
        actionLabel = "Kopiera nu",
        onAction = {}
    )
    ReminderRow(
        title = "Att besluta",
        body = "2 föreslagna · 1 sålt, väntar på köp",
        actionLabel = "Öppna Beslut",
        onAction = {},
        count = 3,
        tone = ReminderRowTone.Accent,
        chevron = true
    )
    Label("ExpandableSection · ExpandableInfoRow")
    ExpandableSection(title = "Ordlista") { Text("Innehållet visas när sektionen fälls ut.") }
    ExpandableInfoRow(explanation = "Årstakten är den genomsnittliga avkastningen per år sedan första köpet.") {
        Text("Årstakt 6,1 %")
    }
}

@Composable
private fun ColumnScope.Fields() {
    var amount by remember { mutableStateOf("12 000") }
    var date by remember { mutableStateOf<KotlinLocalDate?>(KotlinLocalDate(2026, 9, 3)) }
    var type by remember { mutableStateOf<String?>("Sälj") }
    var mode by remember { mutableStateOf("Mörkt") }
    var level by remember { mutableIntStateOf(4) }
    var checked by remember { mutableStateOf(true) }
    var indexOnly by remember { mutableStateOf(false) }
    var followed by remember { mutableStateOf(false) }
    Label("AppTextField (android-ui)")
    AppTextField(amount, { amount = it }, label = "Belopp", suffix = "kr", helper = "Courtage ingår")
    AppTextField("SE00", {}, label = "ISIN", error = "ISIN har 12 tecken")
    Label("DateField (android-ui) · SelectField")
    DateField(label = "Affärsdag", date = date, onDateChange = { date = it })
    SelectField(
        label = "Typ",
        options = listOf("Köp", "Sälj"),
        selected = type,
        optionLabel = { it },
        onSelect = { type = it },
        clearOptionLabel = "Alla"
    )
    Label("ChoiceChipRow · RiskLevelPicker")
    ChoiceChipRow(options = listOf("Ljust", "Mörkt", "Auto"), selected = mode, optionLabel = { it }, onSelect = { mode = it })
    RiskLevelPicker(selected = level, onSelect = { level = it }, marked = 5, markedDescription = "Säljfondens nivå")
    Label("CheckRow · SwitchRow (android-ui) · FollowedToggleRow")
    CheckRow(checked, { checked = it }, label = "Global Indexfond A", supportingText = "120,5 andelar")
    SwitchRow("Bara indexfonder", indexOnly, { indexOnly = it }, subtitle = "Föreslå bara indexfonder")
    SwitchRow("Låst reglage", true, {}, enabled = false)
    FollowedToggleRow(followed = followed, onFollowedChange = { followed = it })
}

@Composable
private fun ColumnScope.Values() {
    Label("PeriodRow · ApproxTaxRow · ValueAsOfRow")
    PeriodRow(label = "Idag", amount = 845.0, fraction = 0.0021)
    PeriodRow(label = "I år", amount = -1_960.0, fraction = -0.008)
    PeriodRow(label = "5 år", amount = null, fraction = null)
    PeriodRow(label = "Sharpe", amount = null, fraction = null, valueText = "0,6")
    ApproxTaxRow(label = "Varav skatt", tax = 3_000.0, basis = "30 % på vinst efter kvittning")
    ValueAsOfRow(navEpochDay = LocalDate.of(2026, 10, 16).toEpochDay())
    Label("ExposureBar")
    ExposureBar(label = "Sverige", fraction = 0.46)
    ExposureBar(label = "Nordamerika", fraction = 0.38, color = MaterialTheme.colorScheme.secondary)
    ExposureBar(label = "Övrigt", fraction = 0.16, color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun ColumnScope.Status() {
    Label("AnalysisStatusBanner · AnalysisGuidanceCard")
    AnalysisStatusBanner(analysis = galleryAnalysis)
    AnalysisGuidanceCard(analysis = galleryAnalysis)
    Label("WorkerStatusIcon · WorkingIndicator · ProgressBar")
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.l), verticalAlignment = Alignment.CenterVertically) {
        WorkerStatusIcon(isRunning = true)
        WorkingIndicator(working = true, contentDescription = "Uppdaterar Innehav")
    }
    ProgressBar(modifier = Modifier.fillMaxWidth())
    ProgressBar(modifier = Modifier.fillMaxWidth(), progress = { 0.6f })
    Label("PullToRefreshContainer")
    PullToRefreshContainer(refreshing = true, onRefresh = {}, modifier = Modifier.height(120.dp)) {
        PeriodRow(label = "Sedan köp", amount = 18_420.0, fraction = 0.091)
    }
    Label("EmptyState (Fonders, kvar på importerna, formuläret och Pågående byte)")
    EmptyState(title = "Inga fonder hittades", body = "Prova ett annat namn eller fondbolag.", icon = Icons.Outlined.PieChart)
    EmptyState(error = AppErrors.map(DataError.Network))
    Label("android-ui EmptyState med knappar · LoadErrorState (CenteredState på flikarna)")
    LibraryEmptyState(
        icon = R.drawable.ic_holdings,
        title = "Inga innehav ännu",
        message = "Lägg till din första fondtransaktion för att se värde och utveckling.",
        actions = listOf(
            EmptyAction("Ny transaktion", {}, UiR.drawable.ic_add),
            EmptyAction("Importera avräkningsnota", {}, R.drawable.ic_upload, ButtonVariant.Secondary)
        )
    )
    LoadErrorState(title = "Kunde inte visa innehållet", error = AppErrors.map(DataError.Database), onRetry = {})
}

@Composable
private fun ColumnScope.Navigation() {
    var tab by remember { mutableIntStateOf(2) }
    var menuOpen by remember { mutableStateOf(false) }
    Label("AppTabHeading · AppTopBar")
    AppTabHeading(title = "Innehav") { WorkerStatusIcon(isRunning = true) }
    AppTopBar(title = "Fond", onBack = {})
    Label("AppFloatingToolbar · AppFabMenu")
    Box(Modifier.fillMaxWidth().height(FAB_AREA_HEIGHT)) {
        AppFloatingToolbar(
            tabs = listOf(
                AppToolbarTab("Översikt", Icons.Outlined.Home),
                AppToolbarTab("Innehav", Icons.Outlined.PieChart),
                AppToolbarTab("Beslut", Icons.Outlined.TaskAlt, badgeCount = 2),
                AppToolbarTab("Händelser", Icons.AutoMirrored.Outlined.List)
            ),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.align(Alignment.BottomStart)
        )
        AppFabMenu(
            expanded = menuOpen,
            onExpandedChange = { menuOpen = it },
            items = listOf(AppFabMenuItem("Ny transaktion", {}), AppFabMenuItem("Bevaka ny fond", {})),
            modifier = Modifier.align(Alignment.BottomEnd),
            handleBack = false
        )
    }
}

@Composable
private fun ColumnScope.Dialogs() {
    var shown by remember { mutableStateOf<GalleryDialog?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        GalleryDialog.entries.forEach { dialog ->
            AppButton(dialog.label, { shown = dialog }, variant = ButtonVariant.Secondary)
        }
    }
    val close = { shown = null }
    when (shown) {
        GalleryDialog.Confirm -> ConfirmDialog(
            title = "Ta bort transaktionen?",
            message = "Köpet av Global Indexfond A den 14 okt tas bort. Det går inte att ångra.",
            confirmLabel = stringResource(R.string.delete),
            onConfirm = close,
            onDismiss = close,
            destructive = true
        )

        GalleryDialog.Single -> ConfirmDialog(
            title = "Kopian är sparad",
            message = "Säkerhetskopian ligger i Drive.",
            confirmLabel = stringResource(R.string.ok),
            onConfirm = close,
            onDismiss = close,
            dismissLabel = null
        )

        GalleryDialog.Loading -> {
            // Väntedialogen går inte att stänga – den försvinner av sig själv, som när importen är klar.
            LaunchedEffect(Unit) {
                delay(LOADING_DIALOG_MS)
                close()
            }
            LoadingDialog(title = "Importerar", body = "Läser avräkningsnotorna …")
        }

        GalleryDialog.Import -> ImportCompleteDialog(importedCount = 7, onDismiss = close)

        null -> Unit
    }
}

/** Dialogerna galleriet kan öppna; [label] är komponentens namn. */
private enum class GalleryDialog(val label: String) {
    Confirm("Confirm"),
    Single("En knapp"),
    Loading("Loading"),
    Import("Import")
}

@Composable
private fun ColumnScope.Library() {
    Label("AppTopBar (ramarnas topprad) · LocalTopBarStatus")
    CompositionLocalProvider(LocalTopBarStatus provides { WorkerStatusIcon(isRunning = true) }) {
        LibraryTopBar("Riskprofil", size = TopBarSize.Small, onBack = {})
    }
}

@Composable
private fun Chart() {
    val end = LocalDate.of(2026, 10, 16).toEpochDay()
    val primary = (0L until 60L).map { day -> (end - 59 + day) to 210.0 + 0.5 * day + 5 * cos(day / 4.0) }
    val comparison = (0L until 60L).map { day -> (end - 59 + day) to 95.0 + 0.1 * day + 2 * cos(day / 6.0) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Label("FundLineChart")
        FundLineChart(points = primary, purchaseEpochDays = listOf(end - 40, end - 10))
        FundLineChart(
            points = primary,
            primaryLabel = "Global Indexfond A",
            comparisonSeries = listOf(ChartSeries("Ränta Kort", comparison))
        )
    }
}

/** Galleriets egen topprad – skiljer dess bakåtpil från exemplens i tester. */
const val COMPONENT_GALLERY_TOP_BAR_TAG = "component_gallery_top_bar"

/** Tillståndsmärken per rad, så att inget bryts mitt i ordet. */
private const val CHIPS_PER_ROW = 3

/** Så länge väntedialogen visas innan den stänger sig själv. */
private const val LOADING_DIALOG_MS = 2_000L

/** Plats för flikraden och plusmenyn i utfällt läge. */
private val FAB_AREA_HEIGHT = 280.dp

/** Fonders toner för märkena, med namnen galleriet visar. */
private val galleryTones = listOf(
    "Primary" to Tone.Primary,
    "Copper" to Tone.Copper,
    "Gain" to Tone.Gain,
    "Tertiary" to Tone.Tertiary,
    "Neutral" to Tone.Neutral,
    "Filled" to Tone.Filled
)

/** Påhittad analys för statusbannern och vägledningen (aldrig riktiga innehav). */
private val galleryAnalysis = FundAnalysisCalc.Analysis(
    keyFigures = FundAnalysisCalc.KeyFigures(
        periodReturns = emptyList(),
        cagr = 0.052,
        currentNav = 184.20,
        gavPerShare = 201.75,
        gavFraction = -0.087,
        portfolioShareFraction = 0.31,
        annualizedVolatility = 0.211,
        sharpeRatio = 0.3,
        riskWindowYears = 3
    ),
    distanceFromHigh = FundAnalysisCalc.DistanceFromHighSignal(SignalLevel.ROD, -0.236),
    trend = FundAnalysisCalc.TrendSignal(SignalLevel.ROD),
    momentum = null,
    status = SignalLevel.ROD,
    profitTake = null
)
