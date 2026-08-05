package com.cleaningbutton.r2finance.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
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

private object Routes {
    const val Accounts = "accounts"
    const val Inbox = "inbox"
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
        Routes.Accounts,
        Routes.Inbox,
        Routes.Categories,
        Routes.More,
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = route == Routes.Accounts,
                        onClick = {
                            navController.navigate(Routes.Accounts) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.AccountBalance, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_accounts)) },
                    )
                    NavigationBarItem(
                        selected = route == Routes.Inbox,
                        onClick = {
                            navController.navigate(Routes.Inbox) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Inbox, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_inbox)) },
                    )
                    NavigationBarItem(
                        selected = route == Routes.Categories,
                        onClick = {
                            navController.navigate(Routes.Categories) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Default.Category, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_categories)) },
                    )
                    NavigationBarItem(
                        selected = route == Routes.More,
                        onClick = {
                            navController.navigate(Routes.More) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
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
