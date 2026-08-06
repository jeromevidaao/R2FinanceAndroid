package com.cleaningbutton.r2finance.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cleaningbutton.r2finance.R
import com.cleaningbutton.r2finance.data.AppContainer
import com.cleaningbutton.r2finance.ui.accounts.AccountsScreen
import com.cleaningbutton.r2finance.ui.categories.CategoriesScreen
import com.cleaningbutton.r2finance.ui.home.HomeScreen
import com.cleaningbutton.r2finance.ui.inbox.InboxScreen
import com.cleaningbutton.r2finance.ui.more.MoreScreen
import com.cleaningbutton.r2finance.ui.register.RegisterScreen
import com.cleaningbutton.r2finance.ui.reports.ReportsScreen

private object Routes {
    const val Home = "home"
    const val Spending = "spending"
    const val Account = "account"
    const val Report = "report"
    const val Categories = "categories"
    const val More = "more"
    const val Register = "register/{accountId}"
    fun register(accountId: String) = "register/$accountId"
}

@Composable
fun AppNavHost(container: AppContainer) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val showBottomBar = route in setOf(
        Routes.Home,
        Routes.Spending,
        Routes.Account,
        Routes.Report,
        Routes.Categories,
        Routes.More,
    )

    fun go(dest: String) {
        navController.navigate(dest) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == Routes.Home,
                        onClick = { go(Routes.Home) },
                        icon = { Icon(Icons.Default.Home, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_home)) },
                    )
                    NavigationBarItem(
                        selected = route == Routes.Spending,
                        onClick = { go(Routes.Spending) },
                        icon = { Icon(Icons.Default.ShoppingCart, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_spending)) },
                    )
                    NavigationBarItem(
                        selected = route == Routes.Account,
                        onClick = { go(Routes.Account) },
                        icon = { Icon(Icons.Default.AccountBalance, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_account)) },
                    )
                    NavigationBarItem(
                        selected = route == Routes.Report,
                        onClick = { go(Routes.Report) },
                        icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_report)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Home,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.Home) {
                HomeScreen(
                    container = container,
                    onOpenSpending = { go(Routes.Spending) },
                    onOpenAccounts = { go(Routes.Account) },
                    onOpenReports = { go(Routes.Report) },
                    onOpenCategories = { go(Routes.Categories) },
                    onOpenMore = { go(Routes.More) },
                )
            }
            composable(Routes.Spending) {
                InboxScreen(container = container)
            }
            composable(Routes.Account) {
                AccountsScreen(
                    container = container,
                    onOpenAccount = { id -> navController.navigate(Routes.register(id)) },
                )
            }
            composable(Routes.Report) {
                ReportsScreen(container = container)
            }
            composable(Routes.Categories) {
                CategoriesScreen(container = container)
            }
            composable(Routes.More) {
                MoreScreen(container = container)
            }
            composable(
                Routes.Register,
                arguments = listOf(navArgument("accountId") { type = NavType.StringType }),
            ) { entry ->
                val accountId = entry.arguments?.getString("accountId").orEmpty()
                RegisterScreen(
                    container = container,
                    accountId = accountId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
