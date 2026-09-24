package com.vasmarfas.UniversalAmbientLight.ui.navigation

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.composable
import androidx.navigation.compose.NavHost
import androidx.navigation.NavHostController
import com.vasmarfas.UniversalAmbientLight.common.remote.PairingPayload
import com.vasmarfas.UniversalAmbientLight.common.remote.RemoteSession
import com.vasmarfas.UniversalAmbientLight.common.util.AnalyticsHelper
import com.vasmarfas.UniversalAmbientLight.common.util.DeviceProfile
import com.vasmarfas.UniversalAmbientLight.R
import com.vasmarfas.UniversalAmbientLight.ui.camera.CameraSetupScreen
import com.vasmarfas.UniversalAmbientLight.ui.home.describeSource
import com.vasmarfas.UniversalAmbientLight.ui.home.describeTarget
import com.vasmarfas.UniversalAmbientLight.ui.home.EffectMode
import com.vasmarfas.UniversalAmbientLight.ui.home.HelpDialog
import com.vasmarfas.UniversalAmbientLight.ui.home.HomeStatus
import com.vasmarfas.UniversalAmbientLight.ui.home.LowRatingDialog
import com.vasmarfas.UniversalAmbientLight.ui.home.MainScreen
import com.vasmarfas.UniversalAmbientLight.ui.home.openGitHubIssues
import com.vasmarfas.UniversalAmbientLight.ui.home.openGooglePlayReview
import com.vasmarfas.UniversalAmbientLight.ui.home.RatingDialog
import com.vasmarfas.UniversalAmbientLight.ui.home.RemoteEntry
import com.vasmarfas.UniversalAmbientLight.ui.home.SupportDialog
import com.vasmarfas.UniversalAmbientLight.ui.home.UrlDialog
import com.vasmarfas.UniversalAmbientLight.ui.led.LedLayoutScreen
import com.vasmarfas.UniversalAmbientLight.ui.remote.LocalRemote
import com.vasmarfas.UniversalAmbientLight.ui.remote.rememberSettingsPreferences
import com.vasmarfas.UniversalAmbientLight.ui.remote.RemoteBanner
import com.vasmarfas.UniversalAmbientLight.ui.remote.RemoteHostScreen
import com.vasmarfas.UniversalAmbientLight.ui.remote.RemoteTvsScreen
import com.vasmarfas.UniversalAmbientLight.ui.settings.SettingsScreen
@Composable
fun AppNavHost(
    navController: NavHostController,
    startDestination: String = Screen.Home.route,
    isRunning: Boolean,
    onToggleClick: () -> Unit,
    onEffectsClick: () -> Unit,
    effectMode: EffectMode,
    lastError: String? = null,
    remotePending: Boolean = false,
    pendingPayload: PairingPayload? = null,
    onPayloadConsumed: () -> Unit = {},
) {
    // QR открыли системной камерой: приложение запустилось по ссылке — сразу к сопряжению
    LaunchedEffect(pendingPayload) {
        if (pendingPayload != null) {
            navController.navigate(Screen.RemoteTvs.route) { launchSingleTop = true }
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Screen.Home.route) {
            val context = LocalContext.current
            val remote = LocalRemote.current
            // rememberSaveable: поворот экрана не должен закрывать открытые диалоги
            var showHelpDialog by rememberSaveable { mutableStateOf(false) }
            var showSupportDialog by rememberSaveable { mutableStateOf(false) }
            var showUrlDialog by rememberSaveable { mutableStateOf<String?>(null) }
            var showRatingDialog by rememberSaveable { mutableStateOf(false) }
            var showLowRatingDialog by rememberSaveable { mutableStateOf(false) }

            val isTv = remember { DeviceProfile.isTv(context) }
            val prefs = rememberSettingsPreferences()

            // Источник захвата и цель перечитываются при возврате с экрана настроек. Именно по
            // ON_RESUME записи стека: currentBackStackEntry — не snapshot-state, и ключ по
            // нему срабатывал бы в произвольные моменты рекомпозиции.
            var captureSource by remember(prefs) {
                mutableStateOf(prefs.getString(R.string.pref_key_capture_source, "screen") ?: "screen")
            }
            var target by remember(prefs) { mutableStateOf(describeTarget(context, prefs)) }
            var source by remember(prefs) { mutableStateOf(describeSource(context, prefs)) }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, prefs) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        captureSource =
                            prefs.getString(R.string.pref_key_capture_source, "screen") ?: "screen"
                        target = describeTarget(context, prefs)
                        source = describeSource(context, prefs)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            LaunchedEffect(Unit) {
                AnalyticsHelper.logScreenView(context, "home", "MainScreen")
            }

            val status = if (remote != null) {
                HomeStatus(
                    running = remote.running,
                    pending = remotePending || (remote.alive && !remote.running),
                    // Пока ждём запуска, прошлая ошибка ТВ только сбивала бы с толку
                    error = if (remotePending) null else lastError ?: remote.error,
                    target = target,
                    source = source
                )
            } else {
                HomeStatus(running = isRunning, error = lastError, target = target, source = source)
            }
            // На ТВ — пустить телефон, на телефоне — управлять телевизором
            val remoteEntry = if (isTv && remote == null) {
                RemoteEntry(stringResource(R.string.remote_host_title), Icons.Default.PhoneAndroid) {
                    navController.navigate(Screen.RemoteHost.route) { launchSingleTop = true }
                }
            } else {
                RemoteEntry(stringResource(R.string.remote_tvs_title), Icons.Default.SettingsRemote) {
                    navController.navigate(Screen.RemoteTvs.route) { launchSingleTop = true }
                }
            }

            MainScreen(
                isRunning = if (remote != null) remote.running else isRunning,
                onToggleClick = onToggleClick,
                // singleTop: дребезг пульта на ТВ кладёт в стек два экрана настроек подряд
                onSettingsClick = {
                    navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                },
                onEffectsClick = onEffectsClick,
                effectMode = effectMode,
                captureSource = captureSource,
                status = status,
                localPreview = remote == null,
                remoteEntry = remoteEntry,
                topContent = {
                    if (remote != null) {
                        RemoteBanner(
                            snapshot = remote,
                            onDisconnect = { RemoteSession.deactivate() },
                            onRetry = { RemoteSession.reconnectNow() },
                            modifier = Modifier.padding(bottom = 24.dp)
                        )
                    }
                },
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

            // Диалог оценки
            if (showRatingDialog) {
                RatingDialog(
                    onDismiss = { showRatingDialog = false },
                    onRatingSelected = { rating ->
                        showRatingDialog = false
                        AnalyticsHelper.logSettingChanged(
                            context,
                            "rating_selected",
                            rating.toString()
                        )
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
                        AnalyticsHelper.logSettingChanged(
                            context,
                            "low_rating_report_issue",
                            "true"
                        )
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
                            // Если ссылку открыть не удалось, диалог оставляем открытым
                        }
                    }
                )
            }

        }
        composable(Screen.Settings.route) {
            val context = LocalContext.current
            // Состояние сбрасывается само с каждой новой записью стека; ключ по
            // currentBackStackEntry здесь ловил бы чужие переходы посреди анимации
            LaunchedEffect(Unit) {
                AnalyticsHelper.logScreenView(context, "settings", "SettingsScreen")
            }
            SettingsScreen(
                onBackClick = { navController.popBackStack() },
                onLedLayoutClick = {
                    navController.navigate(Screen.LedLayout.route) { launchSingleTop = true }
                },
                onCameraSetupClick = {
                    navController.navigate(Screen.CameraSetup.route) { launchSingleTop = true }
                },
                onRemoteHostClick = {
                    navController.navigate(Screen.RemoteHost.route) { launchSingleTop = true }
                },
                onRemoteTvsClick = {
                    navController.navigate(Screen.RemoteTvs.route) { launchSingleTop = true }
                }
            )
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
        composable(Screen.RemoteHost.route) {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                AnalyticsHelper.logScreenView(context, "remote_host", "RemoteHostScreen")
            }
            RemoteHostScreen(onBackClick = { navController.popBackStack() })
        }
        composable(Screen.RemoteTvs.route) {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                AnalyticsHelper.logScreenView(context, "remote_tvs", "RemoteTvsScreen")
            }
            RemoteTvsScreen(
                onBackClick = { navController.popBackStack() },
                onControlTv = {
                    navController.popBackStack(Screen.Home.route, inclusive = false)
                },
                pendingPayload = pendingPayload,
                onPayloadConsumed = onPayloadConsumed
            )
        }
    }
}
