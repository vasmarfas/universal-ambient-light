package com.vasmarfas.UniversalAmbientLight.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Settings : Screen("settings")
    object LedLayout : Screen("led_layout")
    object CameraSetup : Screen("camera_setup")
}
