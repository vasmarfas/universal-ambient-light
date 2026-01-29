package com.vasmarfas.UniversalAmbientLight.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.vasmarfas.UniversalAmbientLight.EffectMode
import com.vasmarfas.UniversalAmbientLight.MainScreen
import com.vasmarfas.UniversalAmbientLight.HelpDialog
import com.vasmarfas.UniversalAmbientLight.SupportDialog
import com.vasmarfas.UniversalAmbientLight.ui.led.LedLayoutScreen
import com.vasmarfas.UniversalAmbientLight.ui.settings.SettingsScreen
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import android.content.Intent
import android.net.Uri

@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Home.route,
    isRunning: Boolean,
    onToggleClick: () -> Unit,
    onEffectsClick: () -> Unit,
    effectMode: EffectMode
) {
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Home.route) {
            val context = LocalContext.current
            var showHelpDialog by remember { mutableStateOf(false) }
            var showSupportDialog by remember { mutableStateOf(false) }

            MainScreen(
                isRunning = isRunning,
                onToggleClick = onToggleClick,
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onEffectsClick = onEffectsClick,
                effectMode = effectMode,
                onHelpClick = { showHelpDialog = true },
                onSupportClick = { showSupportDialog = true }
            )
            
            if (showHelpDialog) {
                HelpDialog(
                    onDismiss = { showHelpDialog = false },
                    onOpenGitHub = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/vasmarfas/universal-ambient-light/blob/master/README.md"))
                        context.startActivity(intent)
                        showHelpDialog = false
                    }
                )
            }
            
            if (showSupportDialog) {
                SupportDialog(
                    onDismiss = { showSupportDialog = false },
                    onOpenSupport = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/vasmarfas/universal-ambient-light/blob/master/SUPPORT.md"))
                        context.startActivity(intent)
                        showSupportDialog = false
                    }
                )
            }
            
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onLedLayoutClick = { navController.navigate(Screen.LedLayout.route) }
            )
        }
        composable(Screen.LedLayout.route) {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                AnalyticsHelper.logLedLayoutOpened(context)
            }
            LedLayoutScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
