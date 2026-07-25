package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.browser.settings.AppAccentColor

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  dynamicColor: Boolean = false,
  accentColor: Color = AppAccentColor.SOUL_PURPLE.color,
  content: @Composable () -> Unit,
) {
  val darkColors = darkColorScheme(
    primary = accentColor,
    onPrimary = if (accentColor == Color.White) Color.Black else Color.White,
    primaryContainer = Color(0xFF141414),
    onPrimaryContainer = accentColor,
    secondary = KivoDarkSecondary,
    onSecondary = KivoDarkOnSecondary,
    tertiary = accentColor,
    onTertiary = if (accentColor == Color.White) Color.Black else Color.White,
    background = KivoDarkBackground,
    onBackground = KivoDarkOnBackground,
    surface = KivoDarkSurface,
    onSurface = KivoDarkOnSurface,
    surfaceVariant = KivoDarkSurface2,
    onSurfaceVariant = KivoDarkTextSecondary,
    outline = KivoDarkBorder
  )

  val lightColors = lightColorScheme(
    primary = accentColor,
    onPrimary = Color.White,
    primaryContainer = accentColor.copy(alpha = 0.12f),
    onPrimaryContainer = accentColor,
    secondary = KivoLightSecondary,
    onSecondary = KivoLightOnSecondary,
    tertiary = accentColor,
    onTertiary = Color.White,
    background = KivoLightBackground,
    onBackground = KivoLightOnBackground,
    surface = KivoLightSurface,
    onSurface = KivoLightOnSurface,
    surfaceVariant = KivoLightSurface,
    onSurfaceVariant = KivoLightSecondaryText,
    outline = KivoLightBorder
  )

  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> darkColors
      else -> lightColors
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}

