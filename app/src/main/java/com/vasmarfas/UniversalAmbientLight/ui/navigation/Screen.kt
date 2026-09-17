package com.vasmarfas.UniversalAmbientLight.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Settings : Screen("settings")
    object LedLayout : Screen("led_layout")
    object CameraSetup : Screen("camera_setup")
    object RemoteHost : Screen("remote_host")
    object RemoteTvs : Screen("remote_tvs")
    object Effects : Screen("effects")
    object TvRemote : Screen("tv_remote")
    object Delay : Screen("delay")
    object Calibration : Screen("calibration")
    object Devices : Screen("devices")
}
