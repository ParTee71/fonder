package se.partee71.fonder.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import se.partee71.fonder.R
import se.partee71.fonder.ui.fond.FondDetaljScreen
import se.partee71.fonder.ui.fondsok.FundSearchScreen
import se.partee71.fonder.ui.imports.ImportHoldingsScreen
import se.partee71.fonder.ui.imports.ImportOrdersScreen
import se.partee71.fonder.ui.portfolj.PortfoljScreen
import se.partee71.fonder.ui.salda.SaldaFonderScreen
import se.partee71.fonder.ui.settings.SettingsScreen
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
            val title = topLevel.firstOrNull { it.route == currentRoute }?.labelRes
            if (title != null) {
                TopAppBar(title = { Text(stringResource(title)) })
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
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(Screen.Portfolj.route) {
                PortfoljScreen(onFundClick = { fundId -> navController.navigate(Routes.fond(fundId)) })
            }
            composable(Screen.Transaktioner.route) {
                TransaktionerScreen()
            }
            composable(Screen.Salda.route) {
                SaldaFonderScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onImportHoldings = { navController.navigate(Routes.IMPORT_HOLDINGS) },
                    onImportOrders = { navController.navigate(Routes.IMPORT_ORDERS) },
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
                ImportHoldingsScreen()
            }
            composable(Routes.IMPORT_ORDERS) {
                ImportOrdersScreen()
            }
        }
    }
}
