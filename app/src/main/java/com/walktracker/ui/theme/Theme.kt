package com.spywalker.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = Color.White,
    secondary = Secondary,
    onSecondary = Color.White,
    secondaryContainer = SecondaryDark,
    onSecondaryContainer = Color.White,
    tertiary = Accent,
    onTertiary = Color.White,
    background = DarkBackground,
    onBackground = TextOnDark,
    surface = DarkSurface,
    onSurface = TextOnDark,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextOnDarkSecondary,
    error = Error,
    onError = Color.White
)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryLight,
    onPrimaryContainer = PrimaryDark,
    secondary = Secondary,
    onSecondary = Color.White,
    secondaryContainer = SecondaryLight,
    onSecondaryContainer = SecondaryDark,
    tertiary = Accent,
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = TextOnLight,
    surface = LightSurface,
    onSurface = TextOnLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextOnLightSecondary,
    error = Error,
    onError = Color.White
)

@Composable
fun SpyWalkerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color Ð²Ñ‹ÐºÐ»ÑŽÑ‡ÐµÐ½ Ð´Ð»Ñ ÐºÐ¾Ð½ÑÐ¸ÑÑ‚ÐµÐ½Ñ‚Ð½Ð¾Ð³Ð¾ Ð±Ñ€ÐµÐ½Ð´Ð¾Ð²Ð¾Ð³Ð¾ Ð´Ð¸Ð·Ð°Ð¹Ð½Ð°
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            // ÐÐµ Ð¸ÑÐ¿Ð¾Ð»ÑŒÐ·ÑƒÐµÐ¼ dynamic color Ð´Ð»Ñ ÑÐ¾Ñ…Ñ€Ð°Ð½ÐµÐ½Ð¸Ñ Ð±Ñ€ÐµÐ½Ð´Ð°
            if (darkTheme) DarkColorScheme else LightColorScheme
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // ÐŸÐ¾Ð»ÑƒÐ¿Ñ€Ð¾Ð·Ñ€Ð°Ñ‡Ð½Ñ‹Ð¹ ÑÑ‚Ð°Ñ‚ÑƒÑ Ð±Ð°Ñ€ Ð´Ð»Ñ immersive ÑÑ„Ñ„ÐµÐºÑ‚Ð° Ð½Ð° ÐºÐ°Ñ€Ñ‚Ðµ
            window.statusBarColor = DarkBackground.copy(alpha = 0.7f).toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

