package se.partee71.fonder.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import se.partee71.fonder.R
import se.partee71.fonder.ui.beslut.BeslutScreen
import se.partee71.fonder.ui.bytesfonster.SwitchWatchScreen
import se.partee71.fonder.ui.bytesfonster.SwitchWatchViewModel
import se.partee71.fonder.ui.components.AppFabMenu
import se.partee71.fonder.ui.components.AppFabMenuDefaults
import se.partee71.fonder.ui.components.AppFabMenuItem
import se.partee71.fonder.ui.components.AppFloatingToolbar
import se.partee71.fonder.ui.components.AppIconButton
import se.partee71.fonder.ui.components.AppIconButtonVariant
import se.partee71.fonder.ui.components.AppTabHeading
import se.partee71.fonder.ui.components.AppToolbarTab
import se.partee71.fonder.ui.components.AppTopBar
import se.partee71.fonder.ui.components.WorkerStatusIcon
import se.partee71.fonder.ui.facit.FacitScreen
import se.partee71.fonder.ui.fond.FondDetaljScreen
import se.partee71.fonder.ui.fondsok.FundSearchScreen
import se.partee71.fonder.ui.hem.HemScreen
import se.partee71.fonder.ui.imports.ImportHoldingsScreen
import se.partee71.fonder.ui.imports.ImportOrdersScreen
import se.partee71.fonder.ui.portfolj.PortfoljScreen
import se.partee71.fonder.ui.riskprofil.RiskProfilScreen
import se.partee71.fonder.ui.settings.SettingsScreen
import se.partee71.fonder.ui.settings.SettingsViewModel
import se.partee71.fonder.ui.transaktioner.TransactionFormScreen
import se.partee71.fonder.ui.transaktioner.TransaktionerScreen

/**
 * Appens navigering: skalet ([AppNavigationShell]) med Fonders skärmar ([fonderEntries]). Körstatusen
 * för chromets bakgrundsindikator (NAV-6) hämtas här, så att skalet går att testa utan Hilt.
 */
@Composable
fun AppNavigation() {
    val workerStatusViewModel: WorkerStatusViewModel = hiltViewModel()
    val isRunning by workerStatusViewModel.isRunning.collectAsStateWithLifecycle()
    val decisionBadgeViewModel: DecisionBadgeViewModel = hiltViewModel()
    val beslutBadgeCount by decisionBadgeViewModel.count.collectAsStateWithLifecycle()

    // Engångsbegäran från Översiktens påminnelse: Inställningar skrollar fram säkerhetskopieringen
    // och kvitterar. Sparad så den överlever rotation mellan trycket och skrollningen.
    val scrollSettingsToBackup = rememberSaveable { mutableStateOf(false) }
    val backStack = rememberAppBackStack()
    val entries = remember(backStack) { fonderEntries(backStack, scrollSettingsToBackup) }

    AppNavigationShell(
        isWorkerRunning = isRunning,
        beslutBadgeCount = beslutBadgeCount,
        backStack = backStack,
        entryProvider = entries,
    )
}

/**
 * Skalet runt `NavDisplay` (NAV-7, NAV-8, UI-14): på en flik en stor rubrik överst
 * ([AppTabHeading]), den flytande flikraden ([AppFloatingToolbar]) och plusmenyn ([AppFabMenu])
 * längs nederkanten; på en skärm djupare i stacken i stället en topprad med bakåtpil
 * ([AppTopBar]) och varken flikrad eller plusmeny. [beslutBadgeCount] är kopparmärket på Beslut
 * (NAV-9) – 0 ritar inget. [entryProvider] ger skärmarna – i appen [fonderEntries], i testerna
 * enkla ersättare.
 *
 * Varje fliks stack dekoreras för sig (sparat tillstånd och ViewModels per post), så en flik som
 * lämnas via flikraden behåller sina skärmar tills man kommer tillbaka; [AppBackStack] släpper dem
 * när fliken lämnas med bakåt.
 */
@Composable
fun AppNavigationShell(
    isWorkerRunning: Boolean,
    beslutBadgeCount: Int = 0,
    backStack: AppBackStack = rememberAppBackStack(),
    entryProvider: (AppKey) -> NavEntry<AppKey>,
) {
    val currentKey = backStack.current
    val currentTab = Screen.forKey(currentKey)

    var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // Menyn hör till flikarna: lämnas fliken (även via systemets bakåt) stängs den. Efter en
    // rotation återställs stacken innan effekten körs, så en öppen meny på en flik står kvar (UI-9).
    LaunchedEffect(currentKey) { if (currentTab == null) fabMenuExpanded = false }

    val decorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator<AppKey>(),
        rememberViewModelStoreNavEntryDecorator<AppKey>(),
    )
    val decoratedStacks = Screen.entries.associateWith { tab ->
        rememberDecoratedNavEntries(backStack.stack(tab), decorators, entryProvider)
    }
    val shownEntries = Screen.entries
        .filter { it == Screen.START || it == backStack.currentTab }
        .flatMap { decoratedStacks.getValue(it) }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (currentTab != null) {
                    TabHeading(currentTab, isWorkerRunning, backStack)
                } else {
                    // Varje skärm djupare i stacken har rubrik och bakåtpil (UI-8, issue #78).
                    titleResFor(currentKey)?.let { title ->
                        AppTopBar(title = stringResource(title), onBack = { backStack.pop() }) {
                            WorkerStatusIcon(isRunning = isWorkerRunning)
                        }
                    }
                }
            },
            bottomBar = {
                if (currentTab != null) {
                    // Flikraden centreras på plusknappens mittlinje och lämnar plats för den till höger.
                    val toolbarVertical = AppFabMenuDefaults.ButtonCenterFromBottom - TOOLBAR_HALF_HEIGHT
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(
                                start = 8.dp,
                                end = AppFabMenuDefaults.ReservedEndWidth,
                                top = toolbarVertical,
                                bottom = toolbarVertical,
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        val tabs = Screen.entries.map { tab ->
                            AppToolbarTab(
                                label = stringResource(tab.labelRes),
                                icon = tab.icon,
                                badgeCount = beslutBadgeCount.takeIf { tab == Screen.Beslut },
                            )
                        }
                        AppFloatingToolbar(
                            tabs = tabs,
                            selectedIndex = Screen.entries.indexOf(currentTab),
                            onSelect = { index -> backStack.select(Screen.entries[index]) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavDisplay(
                entries = shownEntries,
                // imePadding på ett ställe i stället för per skärm (regel 4): appen kör
                // enableEdgeToEdge, och Scaffolds standardinsets är bara systemBars — tangentbordet
                // låg därför **över** transaktionsformulärets Spara-knapp, ISIN-fältet i Fonddetalj
                // och sökfältet i Fondsök. `adjustResize` räcker inte edge-to-edge på API 30+, där
                // IME är en inset appen själv måste konsumera (issue #78).
                modifier = Modifier.padding(innerPadding).imePadding(),
                transitionSpec = Transitions.forward,
                popTransitionSpec = Transitions.back,
                predictivePopTransitionSpec = Transitions.predictiveBack,
                onBack = { backStack.pop() },
            )
            // Bakåt stänger en öppen plusmeny i stället för att lämna fliken – menyns enda bakåthanterare
            // (AppFabMenu får handleBack = false). Den senast registrerade hanteraren tas först, och
            // NavDisplays registreras i Scaffolds subkomposition, alltså efter allt i skalets egen
            // komposition – även efter en rotation. Därför ligger den här efter NavDisplay, i samma
            // subkomposition, och inte i AppFabMenu.
            BackHandler(enabled = fabMenuExpanded && currentTab != null) { fabMenuExpanded = false }
        }
        if (currentTab != null) {
            AppFabMenu(
                expanded = fabMenuExpanded,
                onExpandedChange = { fabMenuExpanded = it },
                items = listOf(
                    AppFabMenuItem(stringResource(R.string.transaktioner_add), { backStack.push(TransactionFormKey) }),
                    AppFabMenuItem(stringResource(R.string.fab_import_orders), { backStack.push(ImportOrdersKey) }),
                    AppFabMenuItem(stringResource(R.string.fab_import_holdings), { backStack.push(ImportHoldingsKey) }),
                    AppFabMenuItem(stringResource(R.string.fab_watch_fund), { backStack.push(FundSearchKey) }),
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding(),
                handleBack = false,
            )
        }
    }
}

/** Halva flikradens höjd (M3:s flytande verktygsrad, 64 dp). */
private val TOOLBAR_HALF_HEIGHT = 32.dp

/**
 * Flikens rubrik med dess egna åtgärder: Inställningar på Översikt (NAV-7). Chromets
 * bakgrundsindikator (NAV-6) på alla flikar. Sålda fonder har ingen egen länk längre – säljen och
 * deras realiserade resultat står i Händelsers lista (SLD-1, NAV-5).
 */
@Composable
private fun TabHeading(tab: Screen, isWorkerRunning: Boolean, backStack: AppBackStack) {
    AppTabHeading(title = stringResource(tab.labelRes)) {
        WorkerStatusIcon(isRunning = isWorkerRunning)
        when (tab) {
            Screen.Oversikt -> AppIconButton(
                icon = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.nav_settings),
                onClick = { backStack.push(SettingsKey) },
                variant = AppIconButtonVariant.Tonal,
            )
            Screen.Innehav, Screen.Beslut, Screen.Handelser -> Unit
        }
    }
}

/**
 * Fonders skärmar: de fyra flikarna och alla skärmar djupare i stacken, en per nyckel. En skärm som
 * stänger sig själv gör det med `popIfTop(key)`, så att ett andra anrop inte stänger skärmen under.
 * [scrollSettingsToBackup] läses när Inställningar ritas, inte när posten skapas, så kvitteringen
 * slår igenom direkt.
 */
fun fonderEntries(
    backStack: AppBackStack,
    scrollSettingsToBackup: MutableState<Boolean>,
): (AppKey) -> NavEntry<AppKey> = entryProvider {
    entry<OversiktKey> {
        HemScreen(
            onFundClick = { fundId -> backStack.push(FondDetaljKey(fundId)) },
            onOpenBeslut = { backStack.select(Screen.Beslut) },
            onOpenBackup = {
                scrollSettingsToBackup.value = true
                backStack.push(SettingsKey)
            },
        )
    }
    entry<InnehavKey> {
        PortfoljScreen(onFundClick = { fundId -> backStack.push(FondDetaljKey(fundId)) })
    }
    entry<BeslutKey> {
        BeslutScreen(
            onOpenSwitchWatch = { watchId -> backStack.push(SwitchWatchKey(watchId)) },
            onOpenRiskProfile = { backStack.push(RiskProfileKey) },
            onOpenFacit = { backStack.push(FacitKey) },
        )
    }
    // Facit (SET-5) — en skärm i stacken med bakåtpil, från Beslut och från Inställningar (NAV-9).
    entry<FacitKey> {
        FacitScreen()
    }
    entry<HandelserKey> {
        TransaktionerScreen(
            onOpenSwitchWatch = { watchId -> backStack.push(SwitchWatchKey(watchId)) },
        )
    }
    entry<SettingsKey> {
        SettingsScreen(
            onImportHoldings = { backStack.push(ImportHoldingsKey) },
            onImportOrders = { backStack.push(ImportOrdersKey) },
            onOpenRiskProfile = { backStack.push(RiskProfileKey) },
            onOpenFacit = { backStack.push(FacitKey) },
            onOpenBenchmarkPicker = { backStack.push(BenchmarkPickerKey) },
            scrollToBackup = scrollSettingsToBackup.value,
            onScrolledToBackup = { scrollSettingsToBackup.value = false },
        )
    }
    entry<FondDetaljKey> { key ->
        FondDetaljScreen(
            fundId = key.fundId,
            onOpenSwitchWatch = { watchId -> backStack.push(SwitchWatchKey(watchId)) },
        )
    }
    entry<FundSearchKey> {
        FundSearchScreen()
    }
    entry<TransactionFormKey> { key ->
        TransactionFormScreen(onSaved = { backStack.popIfTop(key) })
    }
    entry<ImportHoldingsKey> { key ->
        ImportHoldingsScreen(onDone = { backStack.popIfTop(key) })
    }
    entry<ImportOrdersKey> { key ->
        ImportOrdersScreen(onDone = { backStack.popIfTop(key) })
    }
    entry<RiskProfileKey> { key ->
        RiskProfilScreen(onSaved = { backStack.popIfTop(key) })
    }
    // Fondsök i valläge (HEM-10, issue #102): samma skärm, men träffraden pekar ut en
    // jämförelsefond i stället för att lägga till den i bevakningen. Valet skrivs av
    // SettingsViewModel, som redan äger inställningarna — fondsök förblir en sökskärm.
    entry<BenchmarkPickerKey> { key ->
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        FundSearchScreen(
            onPickFund = { fund ->
                settingsViewModel.chooseBenchmark(fund)
                backStack.popIfTop(key)
            },
        )
    }
    entry<SwitchWatchKey> { key ->
        SwitchWatchScreen(
            watchId = key.watchId,
            onAddCandidate = { backStack.push(SwitchWatchPickerKey(key.watchId)) },
            onClosed = { backStack.popIfTop(key) },
        )
    }
    // Fondsök i valläge (ANA-13): samma skärm som HEM-10:s referensval, men träffen
    // läggs till som bevakat alternativ i stället för att bli ny bevakad fond. Egen
    // ViewModel för posten — den får bevakningens id ur sin nyckel.
    entry<SwitchWatchPickerKey> { key ->
        val switchWatchViewModel = hiltViewModel<SwitchWatchViewModel, SwitchWatchViewModel.Factory> {
            it.create(key.watchId)
        }
        FundSearchScreen(
            onPickFund = { fund ->
                switchWatchViewModel.onAddCandidate(fund)
                backStack.popIfTop(key)
            },
        )
    }
}
