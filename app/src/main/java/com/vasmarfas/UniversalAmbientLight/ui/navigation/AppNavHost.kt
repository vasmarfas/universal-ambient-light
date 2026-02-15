package com.vasmarfas.UniversalAmbientLight.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import com.vasmarfas.UniversalAmbientLight.UrlDialog
import com.vasmarfas.UniversalAmbientLight.RatingDialog
import com.vasmarfas.UniversalAmbientLight.LowRatingDialog
import com.vasmarfas.UniversalAmbientLight.openGitHubIssues
import com.vasmarfas.UniversalAmbientLight.openGooglePlayReview
import com.vasmarfas.UniversalAmbientLight.ui.camera.CameraSetupScreen
import com.vasmarfas.UniversalAmbientLight.ui.led.LedLayoutScreen
import com.vasmarfas.UniversalAmbientLight.ui.settings.SettingsScreen
import androidx.compose.ui.platform.LocalContext
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.content.ActivityNotFoundException
import android.content.res.Configuration
import android.app.UiModeManager
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.common.util.Preferences

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
            var showUrlDialog by remember { mutableStateOf<String?>(null) }
            var showRatingDialog by remember { mutableStateOf(false) }
            var showLowRatingDialog by remember { mutableStateOf(false) }
            
            val isTv = remember {
                val uiModeManager = context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
                uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
            }

            // Read capture source from preferences (re-reads when returning from settings)
            val captureSource = remember(navController.currentBackStackEntry) {
                Preferences(context).getString(R.string.pref_key_capture_source, "screen") ?: "screen"
            }

            LaunchedEffect(Unit) {
                AnalyticsHelper.logScreenView(context, "home", "MainScreen")
            }

            MainScreen(
                isRunning = isRunning,
                onToggleClick = onToggleClick,
                onSettingsClick = { navController.navigate(Screen.Settings.route) },
                onEffectsClick = onEffectsClick,
                effectMode = effectMode,
                captureSource = captureSource,
                onHelpClick = { 
                    showHelpDialog = true
                    AnalyticsHelper.logHelpDialogOpened(context)
                },
                onSupportClick = { 
                    showSupportDialog = true
                    AnalyticsHelper.logSupportDialogOpened(context)
                },
                onReportIssueClick = {
                    AnalyticsHelper.logSettingChanged(context, "report_issue_clicked", "true")
                    openGitHubIssues(context)
                },
                onLeaveReviewClick = {
                    AnalyticsHelper.logSettingChanged(context, "leave_review_clicked", "true")
                    showRatingDialog = true
                }
            )
            
            if (showHelpDialog) {
                HelpDialog(
                    onDismiss = { showHelpDialog = false },
                    onOpenGitHub = {
                        AnalyticsHelper.logHelpLinkOpened(context)
                        val url = context.getString(R.string.help_readme_url)
                        showHelpDialog = false
                        
                        if (isTv) {
                            showUrlDialog = url
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                showUrlDialog = url
                            } catch (e: Exception) {
                                showUrlDialog = url
                            }
                        }
                    }
                )
            }
            
            if (showSupportDialog) {
                SupportDialog(
                    onDismiss = { showSupportDialog = false },
                    onOpenSupport = {
                        AnalyticsHelper.logSupportLinkOpened(context)
                        val url = context.getString(R.string.support_url)
                        showSupportDialog = false
                        
                        if (isTv) {
                            showUrlDialog = url
                        } else {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            try {
                                context.startActivity(intent)
                            } catch (e: ActivityNotFoundException) {
                                showUrlDialog = url
                            } catch (e: Exception) {
                                showUrlDialog = url
                            }
                        }
                    }
                )
            }
            
            // Rating dialog
            if (showRatingDialog) {
                RatingDialog(
                    onDismiss = { showRatingDialog = false },
                    onRatingSelected = { rating ->
                        showRatingDialog = false
                        AnalyticsHelper.logSettingChanged(context, "rating_selected", rating.toString())
                        if (rating >= 4) {
                            openGooglePlayReview(context)
                        } else {
                            showLowRatingDialog = true
                        }
                    }
                )
            }
            
            if (showLowRatingDialog) {
                LowRatingDialog(
                    onDismiss = { showLowRatingDialog = false },
                    onReportIssue = {
                        showLowRatingDialog = false
                        AnalyticsHelper.logSettingChanged(context, "low_rating_report_issue", "true")
                        openGitHubIssues(context)
                    }
                )
            }
            
            val urlToShow = showUrlDialog
            if (urlToShow != null && !showHelpDialog && !showSupportDialog && !showRatingDialog && !showLowRatingDialog) {
                UrlDialog(
                    url = urlToShow,
                    onDismiss = { 
                        showUrlDialog = null
                    },
                    onOpenLink = {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlToShow))
                        try {
                            context.startActivity(intent)
                            showUrlDialog = null
                        } catch (e: Exception) {
                            // Keep dialog open if link couldn't be opened
                        }
                    }
                )
            }
            
        }
        composable(Screen.Settings.route) {
            val context = LocalContext.current
            // Use a key based on back stack entry to reset state when navigating
            val backStackEntry = navController.currentBackStackEntry
            key(backStackEntry?.id) {
                LaunchedEffect(Unit) {
                    AnalyticsHelper.logScreenView(context, "settings", "SettingsScreen")
                }
                SettingsScreen(
                    onBackClick = { navController.popBackStack() },
                    onLedLayoutClick = { navController.navigate(Screen.LedLayout.route) },
                    onCameraSetupClick = { navController.navigate(Screen.CameraSetup.route) }
                )
            }
        }
        composable(Screen.LedLayout.route) {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                AnalyticsHelper.logScreenView(context, "led_layout", "LedLayoutScreen")
                AnalyticsHelper.logLedLayoutOpened(context)
            }
            LedLayoutScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
        composable(Screen.CameraSetup.route) {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                AnalyticsHelper.logScreenView(context, "camera_setup", "CameraSetupScreen")
            }
            CameraSetupScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}
