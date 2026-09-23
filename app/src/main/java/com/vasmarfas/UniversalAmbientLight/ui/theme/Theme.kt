package com.vasmarfas.UniversalAmbientLight.ui.theme

import android.app.Activity
import android.content.res.Resources
import android.os.Build
import android.util.Log
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private const val TAG = "AppTheme"

private val DarkColorScheme = darkColorScheme(
    primary = AmbientCyan80,
    secondary = AmbientBlueGrey80,
    tertiary = AmbientAmber80,
    background = AmbientDarkBackground,
    surface = AmbientDarkSurface
)

private val LightColorScheme = lightColorScheme(
    primary = AmbientCyan40,
    secondary = AmbientBlueGrey40,
    tertiary = AmbientAmber40
)

@Composable
fun AppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Динамические цвета доступны с Android 12
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            // Палитру Material You собирают из ресурсов android.R.color.system_*, и часть
            // прошивок их не отдаёт, хотя объявляет Android 12+: Resources.NotFoundException
            // прилетает прямо из composition и роняет запуск MainActivity.
            try {
                if (darkTheme) dynamicDarkColorScheme(context)
                else dynamicLightColorScheme(context)
            } catch (e: Resources.NotFoundException) {
                Log.w(TAG, "Dynamic color palette unavailable, using static scheme", e)
                if (darkTheme) DarkColorScheme else LightColorScheme
            }
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            // view.context может оказаться ContextWrapper (например, во вложенных compose-хостах):
            // идём по цепочке обёрток, а не падаем на жёстком приведении типа.
            val activity = generateSequence<android.content.Context>(view.context) {
                (it as? android.content.ContextWrapper)?.baseContext
            }.firstOrNull { it is Activity } as? Activity ?: return@SideEffect
            val window = activity.window
            val insetsController = WindowCompat.getInsetsController(window, view)

            // Прозрачность статус-бара уже даёт enableEdgeToEdge() в MainActivity —
            // window.statusBarColor был помечен deprecated и триггерил предупреждение
            // Google Play про edge-to-edge даже под API-гейтом.
            insetsController.isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
