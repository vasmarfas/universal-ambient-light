package com.vasmarfas.UniversalAmbientLight.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Settings : Screen("settings")
    object LedLayout : Screen("led_layout?$ARG_OUTPUT={$ARG_OUTPUT}") {
        fun of(output: Int = 0) = "led_layout?$ARG_OUTPUT=$output"
    }

    object CameraSetup : Screen("camera_setup")
    object RemoteHost : Screen("remote_host")
    object RemoteTvs : Screen("remote_tvs")
    object Effects : Screen("effects")
    object TvRemote : Screen("tv_remote")
    object Delay : Screen("delay")
    object Calibration : Screen("calibration")
    object Devices : Screen("devices?$ARG_OUTPUT={$ARG_OUTPUT}") {
        fun of(output: Int = 0) = "devices?$ARG_OUTPUT=$output"
    }

    object OutputList : Screen("extra_outputs")
    object OutputDetails : Screen("extra_output/{$ARG_OUTPUT}") {
        fun of(output: Int) = "extra_output/$output"
    }

    companion object {
        /** Какой контроллер настраивает экран; 0 - основной. */
        const val ARG_OUTPUT = "output"
    }
}
