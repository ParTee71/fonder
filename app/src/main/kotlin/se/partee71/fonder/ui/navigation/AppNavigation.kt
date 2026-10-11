package se.partee71.fonder.ui.navigation

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import se.partee71.fonder.R
import se.partee71.fonder.ui.bytesfonster.SwitchWatchScreen
import se.partee71.fonder.ui.bytesfonster.SwitchWatchViewModel
import se.partee71.fonder.ui.components.AppButton
import se.partee71.fonder.ui.components.AppButtonVariant
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
import se.partee71.fonder.ui.transaktioner.SoldFundsScreen
import se.partee71.fonder.ui.transaktioner.TransactionFormScreen
import se.partee71.fonder.ui.transaktioner.TransaktionerScreen

/**
 * Appens navigering: skalet ([AppNavigationShell]) med Fonders skärmar. Körstatusen för chromets
 * bakgrundsindikator (NAV-6) hämtas här, så att skalet går att testa utan Hilt.
 */
@Composable
fun AppNavigation() {
    val workerStatusViewModel: WorkerStatusViewModel = hiltViewModel()
    val isRunning by workerStatusViewModel.isRunning.collectAsStateWithLifecycle()

    // Engångsbegäran från Översiktens påminnelse: Inställningar skrollar fram säkerhetskopieringen
    // och kvitterar. Sparad så den överlever rotation mellan trycket och skrollningen.
    var scrollSettingsToBackup by rememberSaveable { mutableStateOf(false) }

    AppNavigationShell(isWorkerRunning = isRunning) { navController ->
        fonderDestinations(
            navController = navController,
            scrollSettingsToBackup = scrollSettingsToBackup,
            onScrollSettingsToBackupChange = { scrollSettingsToBackup = it },
        )
    }
}

/**
 * Hur en flik öppnas (NAV-7) — flikraden och Inställningars väg till Facit landar i samma
 * flikstack. Översikt ligger alltid underst, så bakåt från en annan flik går till Översikt, och
 * varje fliks stack sparas och återställs när man byter flik.
 */
fun NavHostController.navigateToTab(screen: Screen) {
    navigate(screen.route) {
        popUpTo(Screen.START.route) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Inställningar ovanpå Översikt (NAV-7) — en skärm i stacken, inte en flik. */
fun NavHostController.navigateToSettings() {
    navigate(Routes.SETTINGS) { launchSingleTop = true }
}

/**
 * Skalet runt [NavHost] (NAV-7, NAV-8, UI-14): på en flik en stor rubrik överst
 * ([AppTabHeading]), den flytande flikraden ([AppFloatingToolbar]) och plusmenyn ([AppFabMenu])
 * längs nederkanten; på en skärm djupare i stacken i stället en topprad med bakåtpil
 * ([AppTopBar]) och varken flikrad eller plusmeny. [destinations] bygger grafen – i appen
 * [fonderDestinations], i testerna enkla ersättare.
 */
@Composable
fun AppNavigationShell(
    isWorkerRunning: Boolean,
    navController: NavHostController = rememberNavController(),
    destinations: NavGraphBuilder.(NavHostController) -> Unit,
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = Screen.forRoute(currentRoute)

    var fabMenuExpanded by rememberSaveable { mutableStateOf(false) }
    // Menyn hör till flikarna: lämnas fliken (även via systemets bakåt) stängs den.
    // Före första kompositionen av grafen är rutten null; då rörs inte menyn (överlever rotation, UI-9).
    LaunchedEffect(currentRoute) { if (currentRoute != null && currentTab == null) fabMenuExpanded = false }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (currentTab != null) {
                    TabHeading(currentTab, isWorkerRunning, navController)
                } else {
                    // Varje skärm djupare i stacken har rubrik och bakåtpil (UI-8, issue #78).
                    titleResFor(currentRoute)?.let { title ->
                        AppTopBar(title = stringResource(title), onBack = { navController.popBackStack() }) {
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
                        val tabs = Screen.entries.map { AppToolbarTab(label = stringResource(it.labelRes), icon = it.icon) }
                        AppFloatingToolbar(
                            tabs = tabs,
                            selectedIndex = Screen.entries.indexOf(currentTab),
                            onSelect = { index -> navController.navigateToTab(Screen.entries[index]) },
                        )
                    }
                }
            },
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.START.route,
                // imePadding på ett ställe i stället för per skärm (regel 4): appen kör
                // enableEdgeToEdge, och Scaffolds standardinsets är bara systemBars — tangentbordet
                // låg därför **över** transaktionsformulärets Spara-knapp, ISIN-fältet i Fonddetalj
                // och sökfältet i Fondsök. `adjustResize` räcker inte edge-to-edge på API 30+, där
                // IME är en inset appen själv måste konsumera (issue #78).
                modifier = Modifier.padding(innerPadding).imePadding(),
            ) {
                destinations(navController)
            }
        }
        if (currentTab != null) {
            AppFabMenu(
                expanded = fabMenuExpanded,
                onExpandedChange = { fabMenuExpanded = it },
                items = listOf(
                    AppFabMenuItem(stringResource(R.string.transaktioner_add), { navController.navigate(Routes.TRANSACTION_FORM) }),
                    AppFabMenuItem(stringResource(R.string.fab_import_orders), { navController.navigate(Routes.IMPORT_ORDERS) }),
                    AppFabMenuItem(stringResource(R.string.fab_import_holdings), { navController.navigate(Routes.IMPORT_HOLDINGS) }),
                    AppFabMenuItem(stringResource(R.string.fab_watch_fund), { navController.navigate(Routes.FUND_SEARCH) }),
                ),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding(),
            )
        }
    }
}

/** Halva flikradens höjd (M3:s flytande verktygsrad, 64 dp). */
private val TOOLBAR_HALF_HEIGHT = 32.dp

/**
 * Flikens rubrik med dess egna åtgärder: Inställningar på Översikt (NAV-7) och länken till Sålda
 * fonder på Händelser (tills steg 3c slår ihop listorna). Chromets bakgrundsindikator (NAV-6) på
 * alla flikar.
 */
@Composable
private fun TabHeading(tab: Screen, isWorkerRunning: Boolean, navController: NavHostController) {
    AppTabHeading(title = stringResource(tab.labelRes)) {
        WorkerStatusIcon(isRunning = isWorkerRunning)
        when (tab) {
            Screen.Oversikt -> AppIconButton(
                icon = Icons.Outlined.Settings,
                contentDescription = stringResource(R.string.nav_settings),
                onClick = { navController.navigateToSettings() },
                variant = AppIconButtonVariant.Tonal,
            )
            Screen.Handelser -> AppButton(
                text = stringResource(R.string.sold_funds_title),
                onClick = { navController.navigate(Routes.SOLD) { launchSingleTop = true } },
                variant = AppButtonVariant.Text,
            )
            Screen.Innehav, Screen.Beslut -> Unit
        }
    }
}

/** Fonders skärmar: de fyra flikarna och alla skärmar djupare i stacken, med sina rutter och argument. */
fun NavGraphBuilder.fonderDestinations(
    navController: NavHostController,
    scrollSettingsToBackup: Boolean,
    onScrollSettingsToBackupChange: (Boolean) -> Unit,
) {
    composable(Screen.Oversikt.route) {
        HemScreen(
            onFundClick = { fundId -> navController.navigate(Routes.fond(fundId)) },
            onOpenSwitchWatch = { watchId -> navController.navigate(Routes.switchWatch(watchId)) },
            onOpenBackup = {
                onScrollSettingsToBackupChange(true)
                navController.navigateToSettings()
            },
        )
    }
    composable(Screen.Innehav.route) {
        PortfoljScreen(onFundClick = { fundId -> navController.navigate(Routes.fond(fundId)) })
    }
    // Beslut är tills vidare Facit (steg 3b bygger skärmen med förslag och pågående byten).
    composable(Screen.Beslut.route) {
        FacitScreen()
    }
    composable(Screen.Handelser.route) {
        TransaktionerScreen()
    }
    composable(Routes.SOLD) {
        SoldFundsScreen(
            onOpenSwitchWatch = { watchId -> navController.navigate(Routes.switchWatch(watchId)) },
        )
    }
    composable(Routes.SETTINGS) {
        SettingsScreen(
            onImportHoldings = { navController.navigate(Routes.IMPORT_HOLDINGS) },
            onImportOrders = { navController.navigate(Routes.IMPORT_ORDERS) },
            onOpenRiskProfile = { navController.navigate(Routes.RISK_PROFILE) },
            onOpenFacit = { navController.navigateToTab(Screen.Beslut) },
            onOpenBenchmarkPicker = { navController.navigate(Routes.BENCHMARK_PICKER) },
            scrollToBackup = scrollSettingsToBackup,
            onScrolledToBackup = { onScrollSettingsToBackupChange(false) },
        )
    }
    composable(
        route = Routes.FOND,
        arguments = listOf(navArgument("fundId") { type = NavType.StringType }),
    ) {
        FondDetaljScreen(
            onOpenSwitchWatch = { watchId -> navController.navigate(Routes.switchWatch(watchId)) },
        )
    }
    composable(Routes.FUND_SEARCH) {
        FundSearchScreen()
    }
    composable(Routes.TRANSACTION_FORM) {
        TransactionFormScreen(onSaved = { navController.popBackStack() })
    }
    composable(Routes.IMPORT_HOLDINGS) {
        ImportHoldingsScreen(onDone = { navController.popBackStack() })
    }
    composable(Routes.IMPORT_ORDERS) {
        ImportOrdersScreen(onDone = { navController.popBackStack() })
    }
    composable(Routes.RISK_PROFILE) {
        RiskProfilScreen(onSaved = { navController.popBackStack() })
    }
    // Fondsök i valläge (HEM-10, issue #102): samma skärm, men träffraden pekar ut en
    // jämförelsefond i stället för att lägga till den i bevakningen. Valet skrivs av
    // SettingsViewModel, som redan äger inställningarna — fondsök förblir en sökskärm.
    composable(Routes.BENCHMARK_PICKER) {
        val settingsViewModel: SettingsViewModel = hiltViewModel()
        FundSearchScreen(
            onPickFund = { fund ->
                settingsViewModel.chooseBenchmark(fund)
                navController.popBackStack()
            },
        )
    }
    composable(
        route = Routes.SWITCH_WATCH,
        arguments = listOf(navArgument("watchId") { type = NavType.StringType }),
    ) { entry ->
        val watchId = entry.arguments?.getString("watchId")?.toLongOrNull()
        SwitchWatchScreen(
            onAddCandidate = { watchId?.let { navController.navigate(Routes.switchWatchPicker(it)) } },
            onClosed = { navController.popBackStack() },
        )
    }
    // Fondsök i valläge (ANA-13): samma skärm som HEM-10:s referensval, men träffen
    // läggs till som bevakat alternativ i stället för att bli ny bevakad fond. Egen
    // ViewModel för rutten — den får bevakningens id ur sina egna navigationsargument.
    composable(
        route = Routes.SWITCH_WATCH_PICKER,
        arguments = listOf(navArgument("watchId") { type = NavType.StringType }),
    ) {
        val switchWatchViewModel: SwitchWatchViewModel = hiltViewModel()
        FundSearchScreen(
            onPickFund = { fund ->
                switchWatchViewModel.onAddCandidate(fund)
                navController.popBackStack()
            },
        )
    }
}
