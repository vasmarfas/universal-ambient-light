package com.vasmarfas.UniversalAmbientLight.ui.tv

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vasmarfas.UniversalAmbientLight.ui.navigation.Screen
import com.vasmarfas.UniversalAmbientLight.MainScreen
import com.vasmarfas.UniversalAmbientLight.ui.settings.SettingsScreen

@Composable
fun TvAppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: String = Screen.Home.route
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Home.route) {
            MainScreen(
                isRunning = false, // Placeholder
                onToggleClick = { /* Placeholder */ },
                onSettingsClick = { navController.navigate(Screen.Settings.route) }
            )
        }
        composable(Screen.Settings.route) {
            // Placeholder for TV Settings
        }
    }
}
