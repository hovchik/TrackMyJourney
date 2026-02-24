package com.trackjourney.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Brand colors
val Primary = Color(0xFF1B6B4A)
val PrimaryLight = Color(0xFF4CAF50)
val PrimaryDark = Color(0xFF0D3F2C)
val Secondary = Color(0xFF2196F3)
val Accent = Color(0xFFF57C00)
val Error = Color(0xFFE53935)
val Surface = Color(0xFFF8FAF9)
val SurfaceDark = Color(0xFF1C1C1E)

val Walking = Color(0xFF4CAF50)
val Running = Color(0xFFFF9800)
val Cycling = Color(0xFF2196F3)
val Driving = Color(0xFF9C27B0)
val Flying = Color(0xFFE91E63)
val Stationary = Color(0xFF607D8B)
val Hiking = Color(0xFF8BC34A)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8E6CC),
    secondary = Secondary,
    onSecondary = Color.White,
    tertiary = Accent,
    error = Error,
    surface = Surface,
    background = Color.White
)

private val DarkColors = darkColorScheme(
    primary = PrimaryLight,
    onPrimary = Color.Black,
    primaryContainer = PrimaryDark,
    secondary = Secondary,
    onSecondary = Color.Black,
    tertiary = Accent,
    error = Error,
    surface = SurfaceDark,
    background = Color(0xFF121212)
)

@Composable
fun TrackMyJourneyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
