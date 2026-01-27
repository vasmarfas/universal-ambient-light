package com.vasmarfas.UniversalAmbientLight.ui.tv

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.vasmarfas.UniversalAmbientLight.EffectMode
import com.vasmarfas.UniversalAmbientLight.MainScreen
import com.vasmarfas.UniversalAmbientLight.ui.navigation.Screen

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
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onEffectsClick = { /* TODO: handle effects on TV UI */ },
                effectMode = EffectMode.RAINBOW
            )
        }
        composable(Screen.Settings.route) {
            // Placeholder for TV Settings
        }
    }
}
