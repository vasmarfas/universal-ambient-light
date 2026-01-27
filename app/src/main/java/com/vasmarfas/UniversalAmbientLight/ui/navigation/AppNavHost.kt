package com.vasmarfas.UniversalAmbientLight.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.vasmarfas.UniversalAmbientLight.MainScreen
import com.vasmarfas.UniversalAmbientLight.ui.settings.SettingsScreen
import com.vasmarfas.UniversalAmbientLight.ui.led.LedLayoutScreen

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Home.route,
    isRunning: Boolean,
    onToggleClick: () -> Unit
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Home.route) {
            MainScreen(
                isRunning = isRunning,
                onToggleClick = onToggleClick,
                onSettingsClick = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onLedLayoutClick = { navController.navigate(Screen.LedLayout.route) }
            )
        }
        composable(Screen.LedLayout.route) {
            LedLayoutScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
