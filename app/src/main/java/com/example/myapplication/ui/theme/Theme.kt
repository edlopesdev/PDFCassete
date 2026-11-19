package com.example.myapplication.ui.theme

import android.app.Activity
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

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    accentColor: Color = Color(0xFFFF9A56),
    backgroundColor: Color = Color(0xFF101111),
    textColor: Color = Color(0xFFF3F0EA),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> darkColorScheme(
            primary = accentColor,
            secondary = accentColor,
            tertiary = accentColor,
            background = backgroundColor,
            surface = backgroundColor,
            onBackground = textColor,
            onSurface = textColor
        )

        else -> lightColorScheme(
            primary = accentColor,
            secondary = accentColor,
            tertiary = accentColor,
            background = backgroundColor,
            surface = backgroundColor,
            onBackground = textColor,
            onSurface = textColor
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}