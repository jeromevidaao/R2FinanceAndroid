package com.cleaningbutton.r2finance.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MoreHoriz
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
import com.cleaningbutton.r2finance.ui.inbox.InboxScreen
import com.cleaningbutton.r2finance.ui.more.MoreScreen
import com.cleaningbutton.r2finance.ui.register.RegisterScreen
import com.cleaningbutton.r2finance.ui.reports.ReportsScreen

private object Routes {
    const val Accounts = "accounts"
    const val Inbox = "inbox"
    const val Categories = "categories"
    const val Reports = "reports"
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
        Routes.Accounts,
        Routes.Inbox,
        Routes.Categories,
        Routes.Reports,
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
                        selected = route == Routes.Accounts,
                        onClick = { go(Routes.Accounts) },
                        icon = { Icon(Icons.Default.AccountBalance, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_accounts)) },
                    )
                    NavigationBarItem(
                        selected = route == Routes.Inbox,
                        onClick = { go(Routes.Inbox) },
                        icon = { Icon(Icons.Default.Inbox, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_inbox)) },
                    )
                    NavigationBarItem(
                        selected = route == Routes.Categories,
                        onClick = { go(Routes.Categories) },
                        icon = { Icon(Icons.Default.Category, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_categories)) },
                    )
                    NavigationBarItem(
                        selected = route == Routes.Reports,
                        onClick = { go(Routes.Reports) },
                        icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_reports)) },
                    )
                    NavigationBarItem(
                        selected = route == Routes.More,
                        onClick = { go(Routes.More) },
                        icon = { Icon(Icons.Default.MoreHoriz, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_more)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Accounts,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.Accounts) {
                AccountsScreen(
                    container = container,
                    onOpenAccount = { id -> navController.navigate(Routes.register(id)) },
                )
            }
            composable(Routes.Inbox) {
                InboxScreen(container = container)
            }
            composable(Routes.Categories) {
                CategoriesScreen(container = container)
            }
            composable(Routes.Reports) {
                ReportsScreen(container = container)
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
