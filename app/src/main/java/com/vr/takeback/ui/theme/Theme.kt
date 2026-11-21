package com.vr.takeback.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Emerald500,
    onPrimary = Color.Black,
    primaryContainer = Emerald900,
    onPrimaryContainer = Emerald100,

    secondary = Indigo500,
    onSecondary = Color.White,
    secondaryContainer = Indigo900,
    onSecondaryContainer = Indigo100,

    tertiary = Amber500,
    onTertiary = Color.Black,
    tertiaryContainer = Amber900,
    onTertiaryContainer = Amber100,

    error = Terracotta500,
    onError = Color.White,
    errorContainer = Terracotta900,
    onErrorContainer = Terracotta100,

    background = Slate950,
    onBackground = Slate200,
    surface = Slate900,
    onSurface = Slate200,
    surfaceVariant = Slate800,
    onSurfaceVariant = Slate400,
    outline = Slate700
)

private val LightColorScheme = lightColorScheme(
    primary = Emerald600,
    onPrimary = Color.White,
    primaryContainer = Emerald100,
    onPrimaryContainer = Emerald900,

    secondary = Indigo600,
    onSecondary = Color.White,
    secondaryContainer = Indigo100,
    onSecondaryContainer = Indigo900,

    tertiary = Amber600,
    onTertiary = Color.White,
    tertiaryContainer = Amber100,
    onTertiaryContainer = Amber900,

    error = Terracotta500,
    onError = Color.White,
    errorContainer = Terracotta100,
    onErrorContainer = Terracotta900,

    background = Slate50,
    onBackground = Slate900Light,
    surface = Color.White,
    onSurface = Slate900Light,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate600,
    outline = Slate200
)

@Composable
fun TakeBackTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Default to our bespoke intentional palette
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
