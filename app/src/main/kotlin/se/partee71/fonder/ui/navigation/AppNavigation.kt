package se.partee71.fonder.ui.navigation

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import se.partee71.fonder.R
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
import se.partee71.fonder.ui.transaktioner.SoldFundsScreen
import se.partee71.fonder.ui.transaktioner.TransactionFormScreen
import se.partee71.fonder.ui.transaktioner.TransaktionerScreen

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val topLevel = Screen.entries
    val showBars = currentRoute in topLevel.map { it.route }

    Scaffold(
        topBar = {
            // Toppnivåerna bär sin titel i Screen, övriga rutter i titleResFor. Utan den andra
            // halvan saknade Fonddetalj, Fondsök, transaktionsformuläret, importflödena och
            // Riskprofil både rubrik och bakåtpil — enda vägen tillbaka var systemgesten, och
            // ingenting i appen berättade var man var (issue #78).
            val topLevelTitle = topLevel.firstOrNull { it.route == currentRoute }?.labelRes
            val title = topLevelTitle ?: titleResFor(currentRoute)
            if (title != null) {
                val workerStatusViewModel: WorkerStatusViewModel = hiltViewModel()
                val isRunning by workerStatusViewModel.isRunning.collectAsStateWithLifecycle()
                TopAppBar(
                    title = { Text(stringResource(title)) },
                    navigationIcon = {
                        if (topLevelTitle == null) {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(R.string.back),
                                )
                            }
                        }
                    },
                    actions = { WorkerStatusIcon(isRunning = isRunning) },
                )
            }
        },
        bottomBar = {
            if (showBars) {
                NavigationBar {
                    topLevel.forEach { screen ->
                        NavigationBarItem(
                            selected = currentRoute == screen.route,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(Screen.START.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(screen.icon, contentDescription = null) },
                            label = { Text(stringResource(screen.labelRes)) },
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            when (currentRoute) {
                Screen.Portfolj.route -> FloatingActionButton(onClick = { navController.navigate(Routes.FUND_SEARCH) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.fondsok_fab))
                }
                Screen.Transaktioner.route -> FloatingActionButton(onClick = { navController.navigate(Routes.TRANSACTION_FORM) }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.transaktioner_add))
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
            composable(Screen.Hem.route) {
                HemScreen(onFundClick = { fundId -> navController.navigate(Routes.fond(fundId)) })
            }
            composable(Screen.Portfolj.route) {
                PortfoljScreen(onFundClick = { fundId -> navController.navigate(Routes.fond(fundId)) })
            }
            composable(Screen.Transaktioner.route) {
                TransaktionerScreen()
            }
            composable(Screen.Salda.route) {
                SoldFundsScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onImportHoldings = { navController.navigate(Routes.IMPORT_HOLDINGS) },
                    onImportOrders = { navController.navigate(Routes.IMPORT_ORDERS) },
                    onOpenRiskProfile = { navController.navigate(Routes.RISK_PROFILE) },
                    onOpenFacit = { navController.navigate(Routes.FACIT) },
                )
            }
            composable(
                route = Routes.FOND,
                arguments = listOf(navArgument("fundId") { type = NavType.StringType }),
            ) {
                FondDetaljScreen()
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
            composable(Routes.FACIT) {
                FacitScreen()
            }
        }
    }
}
