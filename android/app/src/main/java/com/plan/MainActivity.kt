package com.plan

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.plan.ui.day.DayScreen
import com.plan.ui.foods.FoodsScreen
import com.plan.ui.settings.SettingsScreen
import com.plan.ui.theme.PlanTheme
import com.plan.ui.weights.WeightsScreen
import dagger.hilt.android.AndroidEntryPoint

sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    object Day : Screen("day", "Day", Icons.Filled.Today)
    object Foods : Screen("foods", "Foods", Icons.Filled.Restaurant)
    object Weights : Screen("weights", "Weights", Icons.AutoMirrored.Filled.ShowChart)
    object Settings : Screen("settings", "Settings", Icons.Filled.Settings)
}

val bottomNavItems = listOf(
    Screen.Day,
    Screen.Foods,
    Screen.Weights,
    Screen.Settings
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PlanTheme {
                PlanApp()
            }
        }
    }
}

@Composable
private fun PlanTopBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher_round),
            contentDescription = null,
            modifier = Modifier.size(30.dp).clip(CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Plan",
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
fun PlanApp() {
    val navController = rememberNavController()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PlanTopBar() },
        bottomBar = {
            NavigationBar(
                containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.35f),
                tonalElevation = 0.dp
            ) {
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentDestination = navBackStackEntry?.destination
                bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = selected,
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = com.plan.ui.theme.Cyan,
                            selectedTextColor = com.plan.ui.theme.Cyan,
                            indicatorColor = com.plan.ui.theme.Cyan.copy(alpha = 0.15f),
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Day.route,
            // Pad by the scaffold insets AND mark them consumed, so a deeper imePadding() only
            // adds the keyboard height beyond the bottom bar (no double-counted blue gap).
            modifier = Modifier
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            composable(Screen.Day.route) { DayScreen() }
            composable(Screen.Foods.route) { FoodsScreen() }
            composable(Screen.Weights.route) { WeightsScreen() }
            composable(Screen.Settings.route) { SettingsScreen() }
        }
    }
}
