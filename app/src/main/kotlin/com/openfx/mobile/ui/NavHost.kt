package com.openfx.mobile.ui

import androidx.annotation.StringRes
import androidx.compose.ui.res.stringResource
import com.openfx.mobile.R
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.openfx.app.platform.rememberAdaptiveLayoutInfo
import com.openfx.mobile.ui.browser.FileBrowserScreen
import com.openfx.mobile.ui.transfer.DeviceScreen
import com.openfx.mobile.ui.transfer.TransferScreen
import com.openfx.mobile.ui.settings.SettingsScreen

/**
 * 導航頁面定義
 */
sealed class NavItem(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector
) {
    data object Files : NavItem("files", R.string.nav_files, Icons.Filled.Folder)
    data object Devices : NavItem("devices", R.string.nav_devices, Icons.Filled.Devices)
    data object Transfer : NavItem("transfer", R.string.nav_transfer, Icons.Filled.SwapVert)
    data object Settings : NavItem("settings", R.string.nav_settings, Icons.Filled.Settings)
}

private val navItems = listOf(
    NavItem.Files,
    NavItem.Devices,
    NavItem.Transfer,
    NavItem.Settings,
)

/**
 * 自適應導航主機
 *
 * 根據螢幕方向自動切換：
 * - 直向 (手機常態) → 底部導航列 (NavigationBar)
 * - 橫向 (手機橫轉/平板) → 側邊導航軌 (NavigationRail)
 *
 * 這是 Material3 的官方建議做法：
 * https://m3.material.io/components/navigation-rail
 */
@Composable
fun OpenFXNavHost() {
    val transferViewModel: com.openfx.mobile.ui.transfer.TransferViewModel = androidx.hilt.navigation.compose.hiltViewModel()
    val pendingAppInstalls by transferViewModel.pendingAppInstalls.collectAsState()
    val layoutInfo = rememberAdaptiveLayoutInfo()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val navigateTo: (String) -> Unit = { route ->
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    if (layoutInfo.useSideNavigation) {
        // ===== 橫向 / 平板：側邊導航軌 =====
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail {
                Spacer(Modifier.height(8.dp))
                navItems.forEach { item ->
                    NavigationRailItem(
                        icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                        label = { Text(stringResource(item.labelRes), style = MaterialTheme.typography.labelSmall) },
                        selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                        onClick = { navigateTo(item.route) }
                    )
                }
            }

            // 頁面內容
            NavHost(
                navController = navController,
                startDestination = NavItem.Files.route,
                modifier = Modifier.weight(1f)
            ) {
                composable(NavItem.Files.route) { FileBrowserScreen() }
                composable(NavItem.Devices.route) {
                    DeviceScreen(onTransferStarted = { navigateTo(NavItem.Transfer.route) })
                }
                composable(NavItem.Transfer.route) { TransferScreen() }
                composable(NavItem.Settings.route) { SettingsScreen() }
            }
        }
    } else {
        // ===== 直向 (手機常態)：底部導航列 =====
        Scaffold(
            bottomBar = {
                NavigationBar {
                    navItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = stringResource(item.labelRes)) },
                            label = { Text(stringResource(item.labelRes)) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.route } == true,
                            onClick = { navigateTo(item.route) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = NavItem.Files.route,
                modifier = Modifier.padding(innerPadding)
            ) {
                composable(NavItem.Files.route) { FileBrowserScreen() }
                composable(NavItem.Devices.route) {
                    DeviceScreen(onTransferStarted = { navigateTo(NavItem.Transfer.route) })
                }
                composable(NavItem.Transfer.route) { TransferScreen() }
                composable(NavItem.Settings.route) { SettingsScreen() }
            }
        }
    }
    com.openfx.mobile.ui.browser.ReceivedApkInstallPrompt(pendingAppInstalls, transferViewModel::consumeReceivedApp)
}
