package com.nextvm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.nextvm.core.designsystem.theme.NextVmTheme
import com.nextvm.feature.appmanager.AppManagerScreen
import com.nextvm.feature.filemanager.FileManagerScreen
import com.nextvm.feature.launcher.LauncherScreen
import com.nextvm.feature.settings.SettingsScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NextVmTheme {
                NextVmApp()
            }
        }

        // ============ 软件渲染（sandboxsensor 方案 A）============
        // 本机 Adreno 830 的着色器编译器（libllvm-glnext）在编译新的 shader 变体时
        // 会 SIGABRT（RenderThread）。已实测触发点：Card 阴影、NavHost 过渡、圆环 drawArc。
        // 逐个消除无法根治（每加一个 UI 效果就是一次新变体），故对容器 UI 强制软件层：
        // 绘制走 CPU Canvas，完全不创建 GL program。
        // 仅作用于宿主 UI 的 DecorView；guest 在自己的进程渲染，不受影响。
        window.decorView.setLayerType(
            android.view.View.LAYER_TYPE_SOFTWARE,
            null
        )
    }
}

@Composable
fun NextVmApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val navItems = listOf(
        NavItem("launcher", "Home", Icons.Outlined.Home, Icons.Filled.Home),
        NavItem("appmanager", "Apps", Icons.Outlined.Apps, Icons.Filled.Apps),
        NavItem("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
    )

    val currentRoute = currentDestination?.route
    val showBottomBar = currentRoute in navItems.map { it.route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    navItems.forEach { item ->
                        val selected = currentDestination?.hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    if (selected) item.selectedIcon else item.icon,
                                    contentDescription = item.label,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = {
                                Text(
                                    item.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                                )
                            },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "launcher",
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            enterTransition = { fadeIn(tween(300)) },
            exitTransition = { fadeOut(tween(200)) },
            popEnterTransition = { fadeIn(tween(300)) },
            popExitTransition = { fadeOut(tween(200)) }
        ) {
            composable("launcher") {
                LauncherScreen(
                    onNavigateToInstall = { navController.navigate("appmanager") },
                    onNavigateToSettings = { navController.navigate("settings") },
                    onNavigateToRoute = { route -> navController.navigate(route) }
                )
            }
            composable("appmanager") {
                AppManagerScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("settings") {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable("filemanager") {
                FileManagerScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}

private data class NavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector
)
